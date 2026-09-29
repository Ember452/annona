#!/usr/bin/env python3
"""P1a-09b 查询集与语料的准入机检（烧 eval 前先跑，避免重蹈 P1a-09 覆辙）。

P1a-09 首轮的教训（docs/benchmarks/检索基线_20260928.md）：20 条 query / 5 篇语料
把 Recall@3 顶到 1.0000，两档无分辨力。09b 的准入口径（开发计划 P1a-09b 行）：
  * 语料 15–20 篇，其中 3–5 篇近重复（近重复需人工确认，本脚本只提醒不判定）；
  * query ≥60 条，symbol / clause 桶各 ≥15 条；
  * 每条 query 的 text 非空且互不完全重复（堵"复制凑数"——首轮教训就是数量
    达标而无分辨力）、relevant_docs 非空且指向语料目录中真实存在的文件。

用法：
    python scripts/rag-eval/validate-09b.py \
        --queries scripts/rag-eval/queries-09b.json \
        --corpus-dir scripts/rag-eval/corpus-09b
退出码：0 = 全部通过；1 = 有违规（逐条列出）；2 = 用法/文件错误。

近重复语料的判定由人工完成（同主题改写、同代码段换皮等），通过后在本文件
对应的基线文档结论里注明"近重复 n 篇，人工确认"。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

MIN_QUERIES = 60
MIN_BUCKET = {"symbol": 15, "clause": 15}
MIN_CORPUS, MAX_CORPUS = 15, 20


def fail(errors: list[str]) -> None:
    for e in errors:
        print(f"  ✗ {e}")
    print(f"validate-09b：{len(errors)} 处违规，先修复再跑 eval。")
    raise SystemExit(1)


def usage_error(msg: str) -> None:
    """用法/文件类错误走退出码 2：与"规则违规"（退出码 1）区分，供调用方分诊。"""
    print(msg)
    raise SystemExit(2)


def main() -> int:
    parser = argparse.ArgumentParser(description="P1a-09b 查询集与语料准入机检")
    parser.add_argument("--queries", required=True, help="queries-09b.json 路径")
    parser.add_argument("--corpus-dir", required=True, help="语料目录（md 文件）")
    args = parser.parse_args()

    errors: list[str] = []

    queries_path = Path(args.queries)
    corpus_dir = Path(args.corpus_dir)
    if not queries_path.is_file():
        usage_error(f"validate-09b: 找不到 {queries_path}")
    if not corpus_dir.is_dir():
        usage_error(f"validate-09b: 语料目录不存在 {corpus_dir}")

    # 语料数量与清单
    corpus_files = sorted(p.name for p in corpus_dir.glob("*.md"))
    if not (MIN_CORPUS <= len(corpus_files) <= MAX_CORPUS):
        errors.append(f"语料 {len(corpus_files)} 篇，要求 {MIN_CORPUS}–{MAX_CORPUS} 篇")
    corpus_set = set(corpus_files)

    try:
        data = json.loads(queries_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        usage_error(f"validate-09b: {queries_path} 不可读或非 JSON：{exc}")
    queries = data.get("queries")
    if not isinstance(queries, list):
        usage_error("validate-09b: 缺 queries 数组（schema 同 queries.json）")

    # 数量与分桶
    if len(queries) < MIN_QUERIES:
        errors.append(f"query {len(queries)} 条，要求 ≥{MIN_QUERIES}")
    buckets: dict[str, int] = {}
    for q in queries:
        buckets[q.get("bucket", "(缺失)")] = buckets.get(q.get("bucket", "(缺失)"), 0) + 1
    for bucket, minimum in MIN_BUCKET.items():
        if buckets.get(bucket, 0) < minimum:
            errors.append(f"bucket {bucket} 仅 {buckets.get(bucket, 0)} 条，要求 ≥{minimum}")

    # 逐条：id 唯一、text 非空且不重复、relevant_docs 指向真实语料
    seen_ids: set[str] = set()
    seen_texts: dict[str, str] = {}
    for q in queries:
        qid = q.get("id", "(无 id)")
        if qid in seen_ids:
            errors.append(f"{qid}: id 重复")
        seen_ids.add(qid)
        text = str(q.get("text", "")).strip()
        if not text:
            errors.append(f"{qid}: text 为空（bucket={q.get('bucket')}）")
        elif text in seen_texts:
            errors.append(f"{qid}: text 与 {seen_texts[text]} 完全重复（复制凑数挡在烧 eval 之前）")
        else:
            seen_texts[text] = qid
        docs = q.get("relevant_docs")
        if not docs:
            errors.append(f"{qid}: relevant_docs 为空")
            continue
        for doc in docs:
            if doc not in corpus_set:
                errors.append(f"{qid}: relevant_docs 引用了不存在的语料文件 {doc}")

    print(f"语料 {len(corpus_files)} 篇；query {len(queries)} 条，分桶 {dict(sorted(buckets.items()))}")
    if errors:
        fail(errors)
    print("validate-09b：通过。近重复语料（3–5 篇）为人工判定项——确认后记得在基线结论注明。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
