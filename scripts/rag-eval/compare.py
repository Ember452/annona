#!/usr/bin/env python3
"""对比 ≥2 份 eval.py 的 JSON 报告：指标表 + 逐 query 差异明细。

对比语义（借 🅜 scripts/rag-eval/compare.py 的列设计，升级为多 mode 报告）：
  * 任意两轮 run 都可对比——最常见的是 mode 的 A/B（BOTH vs SEMANTIC vs KEYWORD），
    或换 embedding provider / 调参前后的回归对照；
  * 不同 embedding_provider 的两轮数字**不可比较**（检索按 kb_doc.embedding_model
    过滤，候选集根本不同）——检测到 provider 混排时打印显式警告，避免读表人误判；
  * 指标行之外，只列各轮 misses 不一致的 query（命中差异明细），与上游口径一致。

用法：
    python scripts/rag-eval/compare.py out/rag-eval/eval-a.json out/rag-eval/eval-b.json
退出码：0 = 正常输出；1 = 用法错误（少于两份报告 / 文件不可读）。
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

MODE_ORDER = ["BOTH", "SEMANTIC", "KEYWORD"]


def load_reports(paths: list[str]) -> list[dict]:
    reports = []
    for raw in paths:
        path = Path(raw)
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise SystemExit(f"compare: 无法读取 {path}: {exc}")
        if "runs" not in report:
            raise SystemExit(f"compare: {path} 不是 eval.py 的报告（缺 runs 字段）")
        report["_path"] = str(path)
        reports.append(report)
    if len(reports) < 2:
        raise SystemExit("compare: 至少需要两份报告才能对比")
    return reports


def warn_provider_mix(reports: list[dict]) -> None:
    providers = {r.get("embedding_provider") for r in reports}
    if len(providers) > 1:
        print("⚠️  各报告 embedding_provider 不同（" + ", ".join(sorted(providers)) + "）："
              "检索按 embedding_model 过滤，候选集不同——数字不可比较，仅供管线回归参考。\n")


def metric_table(reports: list[dict]) -> None:
    header = f"{'mode':<10} | " + " | ".join(
        f"{r.get('label', r['_path'])} ({r.get('embedding_provider')})" for r in reports
    )
    print(header)
    print("-" * len(header))
    modes = sorted({m for r in reports for m in r["runs"]},
                   key=lambda m: MODE_ORDER.index(m) if m in MODE_ORDER else 99)
    for mode in modes:
        cells = []
        for r in reports:
            run = r["runs"].get(mode)
            if run is None:
                cells.append("(未跑)")
                continue
            cells.append(
                f"R@{r['top_k']}={run['recall_at_k']:.4f} "
                f"MRR={run['mrr_at_k']:.4f} "
                f"P50={run['latency_p50_ms']}ms P95={run['latency_p95_ms']}ms"
            )
        print(f"{mode:<10} | " + " | ".join(cells))
    print()


def bucket_table(reports: list[dict], mode: str) -> None:
    bucket_names = sorted({b for r in reports for b in r["runs"].get(mode, {}).get("buckets", {})})
    if not bucket_names:
        return
    print(f"分桶（{mode}）：" + " | ".join(
        f"{r.get('label', r['_path'])}" for r in reports))
    for b in bucket_names:
        cells = []
        for r in reports:
            stats = r["runs"].get(mode, {}).get("buckets", {}).get(b)
            cells.append(f"n={stats['n']} R={stats['recall']:.4f} MRR={stats['mrr']:.4f}"
                         if stats else "(无)")
        print(f"  {b:<20} | " + " | ".join(cells))
    print()


def miss_diff(reports: list[dict]) -> None:
    """只列各轮 misses 不一致的 query——全轮一致的 miss 是语料/检索的真实短板，不是差异。"""
    baseline, others = reports[0], reports[1:]
    base_misses = {m["id"]: m for run in baseline["runs"].values() for m in run.get("misses", [])}
    diff_rows = []
    for other in others:
        other_misses = {m["id"]: m for run in other["runs"].values() for m in run.get("misses", [])}
        for qid in sorted(set(base_misses) ^ set(other_misses)):
            in_base = base_misses.get(qid)
            in_other = other_misses.get(qid)
            diff_rows.append((qid, "基轮 miss / 对比轮命中" if in_base else "基轮命中 / 对比轮 miss",
                              (in_base or in_other).get("query", ""),
                              (in_base or in_other).get("mode", "")))
    if not diff_rows:
        print("逐 query 差异：无（各轮命中集合一致）")
        return
    print("逐 query 命中差异：")
    for qid, kind, query, mode in diff_rows:
        print(f"  [{mode}] {qid} {kind}：{query}")


def main() -> int:
    parser = argparse.ArgumentParser(description="对比多份 eval.py 报告")
    parser.add_argument("reports", nargs="+", help="eval.py 产出的 JSON 报告路径（≥2 份）")
    args = parser.parse_args()

    reports = load_reports(args.reports)
    warn_provider_mix(reports)
    metric_table(reports)
    primary_mode = MODE_ORDER[0]
    bucket_table(reports, primary_mode)
    miss_diff(reports)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
