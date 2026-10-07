#!/usr/bin/env python3
"""test profile 链路门控键存在性机检（pre-commit 第九道，AGENTS §4 元规则）。

背景：application-test.yaml 靠一串 `annona.<链路>.enabled=false` 键把恢复调度器、
任务流等"强制提前实例化"的后台 bean 关在无 DB 冒烟上下文之外。2026-10-07 在该文件
中段插入 voice 块时，Edit 落在 resume.enabled 与 resume.recovery 之间——recovery
子块被过继给 voice，`annona.resume.recovery.enabled` 键凭空消失（matchIfMissing=true
→ 调度器被装配 → 无 JPA 上下文连坐失败）。键的"存在性"此前无人守：只看文件 diff
完全看不出嵌套被破坏。

规则（保守，只拦可证明的错误）：
  违规（exit 1）：application-test.yaml 解析后，REQUIRED_KEYS 清单里任一键缺失或
  值不为 false。新链路接入时清单与键同批增补（本脚本注释即登记处）。

退出码：0 = 全部键存在且为 false；1 = 缺失/值不符；2 = 文件缺失。
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

TEST_YAML = "annona-server/src/test/resources/application-test.yaml"

# 无 DB 冒烟上下文必须显式关闭的链路门控键（新增后台链路 = 在此登记 + yaml 同批加键）
REQUIRED_KEYS = [
    "annona.startup.require-kek",
    "annona.startup.check-pg-extensions",
    "annona.knowledge.recovery.enabled",
    "annona.knowledge.ingest.enabled",
    "annona.questionbank.generate.enabled",
    "annona.questionbank.recovery.enabled",
    "annona.evaluation.enabled",
    "annona.evaluation.recovery.enabled",
    "annona.resume.enabled",
    "annona.resume.recovery.enabled",
    "annona.voice.enabled",
]


def get_nested(data: dict, dotted: str):
    node = data
    for part in dotted.split("."):
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    return node


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    path = repo_root / TEST_YAML
    if not path.is_file():
        print(f"check-test-gate-keys: 文件缺失 {TEST_YAML}", file=sys.stderr)
        return 2
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    problems: list[str] = []
    for key in REQUIRED_KEYS:
        value = get_nested(data, key)
        if value is None:
            problems.append(f"{key}: 键缺失（嵌套被破坏或漏登记）")
        elif value is not False:
            problems.append(f"{key}: 值为 {value!r}，test profile 必须显式 false")
    if problems:
        print("check-test-gate-keys: test profile 门控键异常（无 DB 冒烟上下文会连坐失败）：",
              file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        print("键清单与登记处见本脚本头部注释；新键随链路接入同批增补。", file=sys.stderr)
        return 1
    print(f"check-test-gate-keys：通过（{len(REQUIRED_KEYS)} 个门控键全部存在且为 false）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
