#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""annona 检索评测（P1a-09）。

跑一轮：注册登录 → 种方向 → 上传 corpus → 轮询 READY → 三档检索打分
  → 写 JSON 报告到 --report-dir 并 POST /api/retrieval/eval-run 留痕。
  多轮对比不在本文件：见同目录的 compare.py（它还会检查各轮 provider 一致性、
  列逐 query 命中差异）。

纯标准库（CI 里不装任何包）。形状沿用 🅜 scripts/rag-eval 的"提交语料 → 逐 query 打分 →
写报告"骨架，两处必须改掉：上游打的是 ES/自建服务且查询标注只有一个 docId（算不出 MRR），
这里改成 relevant_docs 数组 + 分桶；上游没有 provider 口径，这里强制记录。

指标口径见 docs/tests/指标测试-检索.md。**--embedding-provider 必须如实传**：
fake 的向量与语义无关，那一轮数字只能证明管道通了，不能当质量结论。

用法见 docs/tests/指标测试-检索.md（教程含 CI / 本机 Docker / 部署环境三条路径）。
"""

import argparse
import http.cookiejar
import json
import os
import random
import string
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

MODES = ("BOTH", "SEMANTIC", "KEYWORD")


class Api:
    """带 cookie 的最小 JSON/表单客户端；Result.code != 0 直接抛错。"""

    def __init__(self, base_url):
        self.base = base_url.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))

    def _call(self, path, payload=None, method=None, headers=None, data=None):
        # method 缺省按负载推导，**不能写 `or "GET"`**：显式传入的 method 会让 urllib
        # 的 get_method() 不再动态推导，post() 会被硬发成 GET（首轮 CI 405 实测）
        if method is None:
            method = "POST" if (payload is not None or data is not None) else "GET"
        req = urllib.request.Request(self.base + path, data=data, method=method)
        for key, value in (headers or {}).items():
            req.add_header(key, value)
        if payload is not None:
            req.add_header("Content-Type", "application/json")
            req.data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        with self.opener.open(req, timeout=120) as resp:
            body = json.loads(resp.read().decode("utf-8"))
        # 只认 Result 的 code==0（Result.SUCCESS_CODE）。不把"缺 code 字段"当成功：
        # 网关返回的 HTML 页被 json 误解析、或端点改了包装时，宁在调用点报错，
        # 也不要到下一句 KeyError 才发现
        if body.get("code") != 0:
            raise RuntimeError(f"{path} 返回业务错误：{body}")
        return body.get("data")

    def post(self, path, payload=None):
        return self._call(path, payload=payload)

    def get(self, path):
        return self._call(path)

    def upload(self, path, field, filename, content, extra):
        """multipart/form-data：上传讲义并带上方向 id。"""
        boundary = "----annona" + uuid.uuid4().hex
        lines = []
        for key, value in extra.items():
            lines.append(f"--{boundary}\r\nContent-Disposition: form-data; "
                         f'name="{key}"\r\n\r\n{value}\r\n'.encode("utf-8"))
        head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; "
                f"filename=\"{filename}\"\r\nContent-Type: text/markdown\r\n\r\n"
                ).encode("utf-8")
        body = b"".join(lines) + head + content + f"\r\n--{boundary}--\r\n".encode("utf-8")
        return self._call(path, data=body, method="POST",
                          headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})


def random_suffix(n=8):
    return "".join(random.choices(string.ascii_lowercase + string.digits, k=n))


def login_new_user(api, email, password):
    try:
        api.post("/api/auth/register", {"email": email, "password": password})
    except RuntimeError as exc:
        # 同一邮箱被上一轮残留占用时不静默换号继续跑：那会让报告的归属含糊
        if "2001" not in str(exc):
            raise
    api.post("/api/auth/login", {"email": email, "password": password})


def seed_corpus(api, corpus_dir):
    """种一个方向并上传全部讲义，返回 {文件名: docId}。"""
    files = sorted(Path(corpus_dir).glob("*.md"))
    if not files:
        raise SystemExit(f"corpus 目录里没有 .md 文件：{corpus_dir}")
    key = f"rag-eval-{random_suffix()}"
    direction = api.post("/api/directions", {"name": f"评测方向 {key}", "key": key})
    direction_id = direction["id"]
    doc_ids = {}
    for path in files:
        result = api.upload("/api/knowledge/docs", "file", path.name,
                            path.read_bytes(), {"directionId": direction_id})
        doc_ids[path.name] = result["id"]
    return doc_ids


def wait_ready(api, doc_ids, timeout=420):
    """轮询到全部 READY；FAILED 直接把可读原因抛出去（静默跳过会让召回虚低）。"""
    deadline = time.time() + timeout
    pending = dict(doc_ids)
    while pending and time.time() < deadline:
        for name, doc_id in list(pending.items()):
            status = api.get(f"/api/knowledge/docs/{doc_id}/status")
            state = status.get("status")
            if state == "READY":
                pending.pop(name, None)
            elif state == "FAILED":
                raise SystemExit(f"{name} 入库失败：{status.get('error')}")
        time.sleep(2)
    if pending:
        raise SystemExit(f"等待 READY 超时，未完成：{sorted(pending)}")


def score_run(api, queries, doc_ids, top_k, mode):
    """逐 query 检索，累计 Recall@K / MRR@K / 延迟分位与分桶结果。"""
    recall_sum = mrr_sum = 0.0
    latencies = []
    per_bucket = {}
    details = []
    for item in queries:
        payload = {"query": item["text"], "topK": top_k, "mode": mode}
        started = time.monotonic()
        data = api.post("/api/retrieval/query", payload)
        elapsed_ms = int((time.monotonic() - started) * 1000)
        hit_docs = [hit["docId"] for hit in data.get("hits", [])]
        expected = [doc_ids[name] for name in item["relevant_docs"] if name in doc_ids]
        # 检索命中的是「分块」，而 Recall 的分子是「文档」：不去重就会让同一文档的
        # 多个块各计一次。一条相关文档 + 三块全命中 = Recall 3.0（CI 实测：服务端按
        # [0,1] 拒了 1.25，这笔账本来就该响，不该静默写进表）
        ranked_docs = list(dict.fromkeys(hit_docs))
        found = [doc for doc in ranked_docs if doc in expected]
        recall = len(found) / len(expected) if expected else 0.0
        # MRR 用「第一条相关<分块>」的位置，不是去重后的文档位置：否则相关块排在
        # 第三、位次会被算得比实际靠前
        first_relevant = next((i for i, doc in enumerate(hit_docs) if doc in expected), None)
        mrr = 1.0 / (first_relevant + 1) if first_relevant is not None else 0.0
        recall_sum += recall
        mrr_sum += mrr
        latencies.append(data.get("tookMs", elapsed_ms))
        bucket = item.get("bucket", "unbucketed")
        stats = per_bucket.setdefault(bucket, {"n": 0, "recall": 0.0, "mrr": 0.0})
        stats["n"] += 1
        stats["recall"] += recall
        stats["mrr"] += mrr
        if not found:
            details.append({"id": item["id"], "mode": mode, "bucket": bucket,
                            "query": item["text"], "reason": (data.get("diagnostics") or {}).get("reason"),
                            "top_docs": hit_docs[:top_k]})

    n = len(queries) or 1
    for stats in per_bucket.values():
        stats["recall"] = round(stats["recall"] / stats["n"], 4)
        stats["mrr"] = round(stats["mrr"] / stats["n"], 4)
    return {
        "recall_at_k": round(recall_sum / n, 4),
        "mrr_at_k": round(mrr_sum / n, 4),
        "latency_p50_ms": percentile(latencies, 0.5),
        "latency_p95_ms": percentile(latencies, 0.95),
        "buckets": per_bucket,
        "misses": details,
    }


def percentile(values, q):
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(q * (len(ordered) - 1)))))
    return ordered[index]


def run(args):
    api = Api(args.base_url)
    email = args.email or f"rag-eval-{random_suffix()}@annona.local"
    login_new_user(api, email, args.password)
    doc_ids = seed_corpus(api, args.corpus_dir)
    wait_ready(api, doc_ids)
    spec = json.loads(Path(args.queries).read_text(encoding="utf-8"))
    top_k = args.top_k or spec.get("top_k", 3)

    report = {
        "label": args.label or time.strftime("%Y-%m-%d %H:%M"),
        "base_url": args.base_url,
        "embedding_provider": args.embedding_provider,
        "embedding_model": args.embedding_model or "",
        "backend": args.backend,
        "top_k": top_k,
        "query_count": len(spec["queries"]),
        "corpus": sorted(doc_ids),
        "email": email,
        "runs": {},
    }
    for mode in MODES:
        report["runs"][mode] = score_run(api, spec["queries"], doc_ids, top_k, mode)

    out_dir = Path(args.report_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    report_path = out_dir / f"eval-{stamp}.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    if not args.no_record:
        for mode, result in report["runs"].items():
            api.post("/api/retrieval/eval-run", {
                "querySet": str(Path(args.queries).name), "label": report["label"],
                "mode": mode, "backend": args.backend,
                "embeddingProvider": args.embedding_provider,
                "embeddingModel": report["embedding_model"], "topK": top_k,
                "queryCount": report["query_count"], "recallAtK": result["recall_at_k"],
                "mrrAtK": result["mrr_at_k"], "latencyP50Ms": result["latency_p50_ms"],
                "latencyP95Ms": result["latency_p95_ms"], "reportPath": str(report_path)})

    print(f"报告：{report_path}")
    for mode, result in report["runs"].items():
        print(f"  {mode:9s} Recall@{top_k}={result['recall_at_k']:.4f} "
              f"MRR@{top_k}={result['mrr_at_k']:.4f} "
              f"P50={result['latency_p50_ms']}ms P95={result['latency_p95_ms']}ms")
    if args.embedding_provider == "fake":
        print("  ⚠️ provider=fake：本轮只证明管道通了，召回数字不代表质量"
              "（fake 向量与语义无关，且与真模型语料不可混比）")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--queries", default=str(Path(__file__).parent / "queries.json"))
    parser.add_argument("--corpus-dir", default=str(Path(__file__).parent / "corpus"))
    parser.add_argument("--report-dir", default="out/rag-eval")
    parser.add_argument("--label", default="")
    parser.add_argument("--email", default=None)
    parser.add_argument("--password", default="RagEval!2026")
    parser.add_argument("--top-k", type=int, default=None)
    parser.add_argument("--backend", default="pgvector")
    parser.add_argument("--embedding-provider",
                        default=os.environ.get("ANNONA_MODEL_EMBEDDING_PROVIDER", "fake"),
                        choices=("fake", "openai-compatible"),
                        help="默认跟着服务端的配置走（CI 里注入了 Key 就是 openai-compatible）；"
                             "假向量那一轮不得当质量结论")
    parser.add_argument("--embedding-model", default="")
    parser.add_argument("--no-record", action="store_true", help="只出报告，不写 eval-run 表")
    args = parser.parse_args()

    run(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
