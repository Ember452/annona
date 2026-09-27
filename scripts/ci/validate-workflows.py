#!/usr/bin/env python3
"""校验 .github/workflows 下所有 workflow：YAML 可解析 + 三条结构规则。

为什么需要它：GitHub 对**无效**的 workflow 文件是静默处理——不运行、不产生
check run，只在 Actions 页面上给一行"file is invalid"。于是出现过这样的事故：
一次 CI 改动让 ci.yml 解析失败，12 个 commit 推上去后**一条检查都没跑**，
而 branch protection 还在等 5 个永远不会上报的必需检查。

语法根因也很典型：YAML 普通标量里出现 `: `（冒号+空格）会被当作映射分隔符，
所以 `run: grep "Tests run: *..."` 这种写法必须用块形 scalar 或引号包起来。

结构规则（各对应一次真实踩坑，667cacb 假绿复盘 / P0 总结 D9）：
  1. 管道吞退出码：`mvn | tee`、`docker save | gzip` 这类"失败也返回 0"的管道
     必须在同一 run 脚本里 `set -o pipefail`（否则构建失败被下游吞成绿）。
  2. gate 覆盖：名为 gate 的汇总 job，其 needs 必须包含文件内其余全部 job——
     新增 blocking job 忘写进 needs 时，该 job 红了 gate 仍绿。
  3. secrets 进 job 级 if：GitHub 的 job-level `if` 不支持 secrets 上下文，
     表达式静默为空（step 级 if 可以用，不在检查范围）。

用法：
    python scripts/ci/validate-workflows.py
退出码：0 = 全部通过；1 = 至少一处问题（打印文件与说明）。
"""

from __future__ import annotations

import re
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

# 管道左侧是"它的失败必须 visible"的命令：构建、测试、产物生成。
# grep/sort 这类诊断性管道不在此列（失败本来就该被容忍，不制造噪声）。
PIPEFAIL_REQUIRED_PREFIXES = (
    "./mvnw",
    "mvnw",
    "mvn ",
    "docker save",
    "docker build",
    "docker compose",  # 含带 -f/--env-file 的实际写法
    "pnpm build",
    "pnpm test",
    "pnpm typecheck",
)
# "set -o pipefail" / "set -euo pipefail" / "set -eu -o pipefail" 等分写形式统一按
# "脚本里出现过 pipefail" 判定（宁可宽松到这里，不能漏）
PIPEFAIL_PATTERN = re.compile(r"pipefail")


def describe(path: Path, exc: yaml.YAMLError) -> str:
    mark = getattr(exc, "problem_mark", None)
    where = f"line {mark.line + 1}, column {mark.column + 1}" if mark else "unknown"
    problem = str(getattr(exc, "problem", exc)).strip()
    snippet = str(getattr(exc, "problem_snippet", "") or "").strip()
    text = f"{path.name}: {where}: {problem}"
    if snippet:
        text += "\n  " + snippet.replace("\n", "\n  ")
    return text


def check_pipefail(path: Path, name: str, job: dict, problems: list[str]) -> None:
    """规则 1：构建/测试/产物命令进管道时，同一 run 脚本必须开 pipefail。"""
    for i, step in enumerate(job.get("steps") or []):
        script = step.get("run")
        if not isinstance(script, str):
            continue
        has_pipefail = bool(PIPEFAIL_PATTERN.search(script))
        for line in script.splitlines():
            stripped = line.strip()
            if "|" not in stripped:
                continue
            first = stripped.split("|", 1)[0].strip()
            if first.startswith(PIPEFAIL_REQUIRED_PREFIXES) and not has_pipefail:
                problems.append(
                    f"{path.name}: job '{name}' step #{i + 1}: "
                    f"管道左侧是构建/测试命令但未 set -o pipefail（失败会被下游吞掉，"
                    f"667cacb 假绿复盘）：{first[:80]}"
                )
                break


def check_gate_coverage(path: Path, jobs: dict, problems: list[str]) -> None:
    """规则 2：gate 的 needs 必须覆盖文件内其余全部 job。"""
    gate = jobs.get("gate")
    if not isinstance(gate, dict):
        return
    others = set(jobs) - {"gate"}
    needs = set(gate.get("needs") or [])
    missing = others - needs
    if missing:
        problems.append(
            f"{path.name}: gate.needs 缺少 {sorted(missing)}——这些 job 红了 gate 仍会绿"
        )


def check_secrets_in_job_if(path: Path, jobs: dict, problems: list[str]) -> None:
    """规则 3：job 级 if 不支持 secrets 上下文（表达式静默为空）。"""
    for name, job in jobs.items():
        cond = job.get("if")
        if isinstance(cond, str) and "secrets." in cond:
            problems.append(
                f"{path.name}: job '{name}' 的 if 引用了 secrets 上下文——"
                f"job 级 if 不支持（静默为空），需经 env 中转或下沉到 step 级"
            )


def main() -> int:
    files = sorted(WORKFLOW_DIR.glob("*.yml")) + sorted(WORKFLOW_DIR.glob("*.yaml"))
    if not files:
        sys.stderr.write(f"validate-workflows: {WORKFLOW_DIR} 下没有找到任何 workflow 文件\n")
        return 1

    problems: list[str] = []
    for path in files:
        try:
            with path.open(encoding="utf-8") as handle:
                doc = yaml.safe_load(handle)
        except yaml.YAMLError as exc:
            problems.append(describe(path, exc))
            continue
        jobs = (doc or {}).get("jobs") or {}
        if not isinstance(jobs, dict):
            problems.append(f"{path.name}: jobs 不是映射，无法做结构检查")
            continue
        for name, job in jobs.items():
            if isinstance(job, dict):
                check_pipefail(path, name, job, problems)
        check_gate_coverage(path, jobs, problems)
        check_secrets_in_job_if(path, jobs, problems)

    if problems:
        for p in problems:
            print(p, file=sys.stderr)
        print(f"\n{len(problems)} 个问题，GitHub 会静默不运行或假绿。", file=sys.stderr)
        return 1
    print(f"全部 {len(files)} 个 workflow 文件解析与结构检查通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
