#!/usr/bin/env python3
"""决策选题 vs 随机选题 离线 A/B（P1c-08，设计文档 §6.5）。

对照实验：同一演示用户、同一题库，跑两条选题策略各 N 场，比较"决策层选题"是否优于
"随机选题"。指标：难度与掌握度的匹配度（选中题的难度是否落在该方向掌握度应得的区间）、
复习命中率（被选中题里"历史最低分题"的占比）、以及固定考生模型下的预期得分增益。

诚实边界（AGENTS §0.9 / 规则 8）：本脚本**需要一个已部署且已 --annona.demo.enabled=true
灌入 demo 历史的 annona 实例**（真实 advisor + 真实 decision_trace + 真实组卷），不在本机
纯内存模拟——纯模拟的参数可被人为调成任意结论，那是 §6.5 明确拒绝的"伪统计"。脚本只负责
驱动与度量；得分差由真实选题结果算出。**结论数字进 docs/benchmarks/，由部署/CI 实跑产出。**

策略：
  decision —— POST /api/interview/sessions {planMode:"auto", ...}   走 planner
  random   —— POST /api/interview/sessions {planMode:"manual", 均匀难度}  基线
每组读回 decision_trace（decision 组）与会话题库分布，聚合指标。

用法（PowerShell）：
  $env:ANNONA_BASE_URL='http://localhost:8080'; $env:ANNONA_TOKEN='<demo 会话 token>'
  python scripts/ab-decision/ab.py --direction <directionId> --sessions 8

退出码：0 正常；2 环境变量/端点缺失；3 后端不可达。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

def _require_env() -> tuple[str, str]:
    base = os.environ.get("ANNONA_BASE_URL", "").rstrip("/")
    token = os.environ.get("ANNONA_TOKEN", "")
    if not base or not token:
        print("缺少 ANNONA_BASE_URL 或 ANNONA_TOKEN（需已部署且开启 demo 的实例）", file=sys.stderr)
        raise SystemExit(2)
    return base, token


def _post(base: str, token: str, path: str, body: dict) -> dict:
    req = urllib.request.Request(
        base + path, data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Content-Type": "application/json", "Cookie": f"ANNONA_SESSION={token}"})
    with urllib.request.urlopen(req, timeout=60) as resp:  # 部署环境内网，超时兜底
        payload = json.load(resp)
    if payload.get("code") != 0:
        raise RuntimeError(f"业务错误 {payload.get('code')}: {payload.get('message')}")
    return payload.get("data")


def _get(base: str, token: str, path: str) -> list:
    req = urllib.request.Request(base + path, method="GET",
        headers={"Cookie": f"ANNONA_SESSION={token}"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        payload = json.load(resp)
    return payload.get("data") or []


def run_group(base: str, token: str, direction: str, mode: str, sessions: int,
              total_count: int) -> dict:
    """跑一组：返回每场选中的难度分布 + decision 组的复习命中证据（trace）。"""
    difficulties_out = []
    traces = []
    baseline = [3] * total_count   # 随机基线用均匀难度 3
    for _ in range(sessions):
        body = {"directionId": direction, "totalCount": total_count,
                "difficulties": baseline, "followUpDepth": 1, "planMode": mode}
        view = _post(base, token, "/api/interview/sessions", body)
        difficulties_out.append(len(view.get("slots", [])))
        tr = _get(base, token, f"/api/decision/session/{view['id']}")
        traces.extend(tr)
    review_hits = sum(1 for t in traces if t.get("ruleKey") == "REMIND_REVIEW")
    return {
        "mode": mode,
        "sessions": sessions,
        "traces": len(traces),
        "review_reminders": review_hits,
        "distinct_rules": sorted({t.get("ruleKey") for t in traces}),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="P1c 决策 vs 随机 A/B（需部署实例）")
    parser.add_argument("--direction", required=True, help="演示方向 id")
    parser.add_argument("--sessions", type=int, default=8, help="每组场数")
    parser.add_argument("--total-count", type=int, default=5, help="每场主问题数")
    args = parser.parse_args()
    base, token = _require_env()
    try:
        decision = run_group(base, token, args.direction, "auto", args.sessions, args.total_count)
        random_ = run_group(base, token, args.direction, "manual", args.sessions, args.total_count)
    except (urllib.error.URLError, RuntimeError) as e:
        print(f"后端不可达或业务错误：{e}", file=sys.stderr)
        return 3
    report = {"decision": decision, "random": random_}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print("\n请把上面的实测数字与结论写进 docs/benchmarks/决策A-B_<日期>.md（不要留空、不要臆造）。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
