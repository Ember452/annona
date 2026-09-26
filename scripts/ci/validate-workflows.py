#!/usr/bin/env python3
"""校验 .github/workflows 下所有 YAML 能被解析。

为什么需要它：GitHub 对**无效**的 workflow 文件是静默处理——不运行、不产生
check run，只在 Actions 页面上给一行"file is invalid"。于是出现过这样的事故：
一次 CI 改动让 ci.yml 解析失败，12 个 commit 推上去后**一条检查都没跑**，
而 branch protection 还在等 5 个永远不会上报的必需检查。

语法根因也很典型：YAML 普通标量里出现 `: `（冒号+空格）会被当作映射分隔符，
所以 `run: grep "Tests run: *..."` 这种写法必须用块形 scalar 或引号包起来。

用法：
    python scripts/ci/validate-workflows.py
退出码：0 = 全部可解析；1 = 至少一个文件语法错误（并打印行号与上下文）。
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - 环境缺依赖时必须显式告知，不能静默通过
    sys.stderr.write(
        "validate-workflows: 需要 PyYAML（pip install pyyaml）。\n"
        "跳过校验就等于放弃这道防线，因此按失败处理。\n"
    )
    raise SystemExit(1)

WORKFLOW_DIR = Path(".github/workflows")


def describe(path: Path, exc: yaml.YAMLError) -> str:
    mark = getattr(exc, "problem_mark", None)
    where = f"line {mark.line + 1}, column {mark.column + 1}" if mark else "unknown"
    problem = str(getattr(exc, "problem", exc)).strip()
    snippet = str(getattr(exc, "problem_snippet", "") or "").strip()
    text = f"{path.name}: {where}: {problem}"
    if snippet:
        text += "\n  " + snippet.replace("\n", "\n  ")
    return text


def main() -> int:
    files = sorted(WORKFLOW_DIR.glob("*.yml")) + sorted(WORKFLOW_DIR.glob("*.yaml"))
    if not files:
        sys.stderr.write(f"validate-workflows: {WORKFLOW_DIR} 下没有找到任何 workflow 文件\n")
        return 1

    broken = 0
    for path in files:
        try:
            with path.open(encoding="utf-8") as handle:
                yaml.safe_load(handle)
        except yaml.YAMLError as exc:
            broken += 1
            print(describe(path, exc), file=sys.stderr)
        else:
            print(f"OK  {path.name}")

    if broken:
        print(f"\n{broken} 个 workflow 文件语法无效，GitHub 会静默不运行它们。", file=sys.stderr)
        return 1
    print(f"\n全部 {len(files)} 个 workflow 文件解析通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
