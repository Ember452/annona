#!/usr/bin/env python3
"""原生查询 datetime 标量硬转型机检（pre-commit 第八道，AGENTS §4 元规则）。

背景：原生 @Query 的 date/timestamp 标量 Java 类型随 Hibernate 版本漂移——
Hibernate 6.x 默认 java.sql.Date/Timestamp（prefer_jdbc_datetime_types=true），
Hibernate 7（Boot 4）起默认 LocalDate/LocalDateTime（该开关默认翻转为 false）。
按单一类型硬转型在 6.x 上全绿、升到 7 后真库 CI 才炸：StudyStatsFlowIT 三连
ClassCastException（java.time.LocalDate cannot be cast to java.sql.Date）。
timestamptz 轴在 EvaluationSignalPortImpl#toInstant 已付过一次学费，date 轴
2026-10-04 又付一次——同一约定踩坑两次，按元规则配同批机检。

规则（保守，只拦可证明的错误）：
  违规（exit 1）：src/main 里对 java.sql.Date / java.sql.Time / java.sql.Timestamp
  的强制转型 `(java.sql.Xxx)`。正确写法是 switch 模式匹配双类型适配
  （先例 StudyStatsService#toLocalDate、EvaluationSignalPortImpl#toInstant——
  模式匹配不是转型，不会命中本门禁）。
  已知限制：裸类型转型（如 `(Timestamp) row[0]`）不拦——与 java.util.Date 同名，
  误报面大；真出现再升级门禁。

退出码：0 = 无违规；1 = 有违规；2 = 源目录缺失（配置错误与违规分离，同族口径）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

MODULES = ("annona-server", "annona-common", "annona-spi", "annona-infrastructure")

CAST = re.compile(r"\(\s*java\.sql\.(?:Date|Time|Timestamp)\s*\)")


def scan(repo_root: Path) -> list[str]:
    violations: list[str] = []
    for module in MODULES:
        src = repo_root / module / "src" / "main" / "java"
        if not src.is_dir():
            continue
        for path in sorted(src.rglob("*.java")):
            for line_no, line in enumerate(
                path.read_text(encoding="utf-8").splitlines(), start=1
            ):
                if CAST.search(line):
                    rel = path.relative_to(repo_root).as_posix()
                    violations.append(f"{rel}:{line_no}: java.sql datetime 硬转型（{line.strip()[:80]}）")
    return violations


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    roots = [repo_root / m / "src" / "main" / "java" for m in MODULES]
    if not any(r.is_dir() for r in roots):
        print("check-native-datetime-cast: 源目录缺失", file=sys.stderr)
        return 2
    violations = scan(repo_root)
    if violations:
        print(
            "check-native-datetime-cast: 原生查询 datetime 标量的 Java 类型随 Hibernate 版本"
            "漂移（7 起 LocalDate，6.x 起 java.sql.*），硬转型必在一边炸（CI docker-it 实测）：",
            file=sys.stderr,
        )
        for v in violations:
            print(f"  {v}", file=sys.stderr)
        print(
            "改法：switch 模式匹配双类型适配"
            "（先例 StudyStatsService#toLocalDate、EvaluationSignalPortImpl#toInstant）。",
            file=sys.stderr,
        )
        return 1
    print("check-native-datetime-cast：通过（src/main 内 java.sql datetime 硬转型为 0）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
