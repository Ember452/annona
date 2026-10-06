#!/usr/bin/env python3
"""原生查询真库地雷机检（pre-commit 第八道，AGENTS §4 元规则）。

本机 slice（mock 仓库/EM）探不到、只在真 PG 上炸的原生查询形状，踩坑一次配一道机检：

规则 1 —— datetime 标量硬转型（2026-10-04，date 轴）：
  原生 @Query 的 date/timestamp 标量 Java 类型随 Hibernate 版本漂移——Hibernate 6.x
  默认 java.sql.Date/Timestamp（prefer_jdbc_datetime_types=true），Hibernate 7（Boot 4）
  起默认 LocalDate/LocalDateTime（该开关默认翻转为 false）。按单一类型硬转型在 6.x 上
  全绿、升到 7 后真库 CI 才炸：StudyStatsFlowIT 三连 ClassCastException。timestamptz 轴
  在 EvaluationSignalPortImpl#toInstant 已付过一次学费，date 轴又付一次。
  违规：src/main 里对 java.sql.Date / java.sql.Time / java.sql.Timestamp 的强制转型
  `(java.sql.Xxx)`。正确写法是 switch 模式匹配双类型适配（先例
  StudyStatsService#toLocalDate、EvaluationSignalPortImpl#toInstant——模式匹配不是转型）。
  已知限制：裸类型转型（如 `(Timestamp) row[0]`）不拦——与 java.util.Date 同名，
  误报面大；真出现再升级门禁。

规则 2 —— 命名参数写进 GROUP BY / ORDER BY 表达式（2026-10-06，同日聚合查询）：
  命名参数在每个出现处展开成独立占位符，PG 的分组匹配要求表达式逐字一致，
  select 里的 cast(start_at at time zone $1) 与 group by 里的 $2 不被认作同一表达式，
  报 "column ... must appear in the GROUP BY clause"（docker-it 实测）。
  违规：nativeQuery = true 的 @Query，其 GROUP BY / ORDER BY 段内出现 :param
  （LIMIT 里的 :param 是值位置，合法——先例 findWeakestQuestionIds 的 limit :limit）。
  正确写法：group by 1 order by 1 按序号分组，参数只出现在 select 行
  （先例 StudySessionRepository#aggregateDailyQuality）。

退出码：0 = 无违规；1 = 有违规；2 = 源目录缺失（配置错误与违规分离，同族口径）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

MODULES = ("annona-server", "annona-common", "annona-spi", "annona-infrastructure")
REPOSITORY_DIR = "annona-server/src/main/java"

CAST = re.compile(r"\(\s*java\.sql\.(?:Date|Time|Timestamp)\s*\)")
QUERY_START = re.compile(r"@Query\b")
DECL = re.compile(r"^\s*(?:void|Integer|int|Long|long|boolean|Boolean|List<.+?>|Set<.+?>"
                  r"|Optional<.+?>|Page<.+?>|[A-Z]\w*)\s+\w+\s*\(")
NATIVE_TRUE = re.compile(r"nativeQuery\s*=\s*true")
GROUP_ORDER = re.compile(r"\bgroup\s+by\b(.*?)(?=\border\s+by\b|\blimit\b|$)"
                         r"|\border\s+by\b(.*?)(?=\blimit\b|$)", re.I | re.S)
NAMED_PARAM = re.compile(r":[A-Za-z_]\w*")


def scan_datetime_casts(repo_root: Path) -> list[str]:
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


def native_query_blocks(text: str) -> list[str]:
    """聚合 @Query 注解块（含续行），到方法声明行为止——与
    check-modifying-native-select 同款逐行结构解析，宁可宽聚合不漏判。"""
    blocks: list[str] = []
    buf: list[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        if QUERY_START.search(line):
            buf = [line]
            continue
        if buf:
            if DECL.match(raw):
                blocks.append("\n".join(buf))
                buf = []
            else:
                buf.append(line)
    return [b for b in blocks if NATIVE_TRUE.search(b)]


def scan_group_order_params(repo_root: Path) -> list[str]:
    violations: list[str] = []
    src = repo_root / REPOSITORY_DIR
    if not src.is_dir():
        return violations
    for path in sorted(src.rglob("*Repository.java")):
        text = path.read_text(encoding="utf-8")
        for block in native_query_blocks(text):
            sql = " ".join(re.findall(r'"([^"]*)"', block))
            line_no = text[: text.find(block.split("\n")[0])].count("\n") + 1
            rel = path.relative_to(repo_root).as_posix()
            for seg in GROUP_ORDER.findall(sql):
                segment = seg[0] or seg[1]
                if NAMED_PARAM.search(segment):
                    violations.append(
                        f"{rel}:{line_no}: 原生查询 GROUP BY/ORDER BY 内含命名参数"
                        f"（PG 逐字匹配分组表达式必炸，改 group by 1 序号）")
    return violations


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    roots = [repo_root / m / "src" / "main" / "java" for m in MODULES]
    if not any(r.is_dir() for r in roots):
        print("check-native-datetime-cast: 源目录缺失", file=sys.stderr)
        return 2
    violations = scan_datetime_casts(repo_root) + scan_group_order_params(repo_root)
    if violations:
        print("check-native-datetime-cast: 原生查询真库地雷（本机 slice 探不到，CI 实测）：",
              file=sys.stderr)
        for v in violations:
            print(f"  {v}", file=sys.stderr)
        print("改法：datetime 标量用 switch 模式匹配双类型适配"
              "（StudyStatsService#toLocalDate）；GROUP BY/ORDER BY 改序号 group by 1。",
              file=sys.stderr)
        return 1
    print("check-native-datetime-cast：通过（datetime 硬转型 0，GROUP BY/ORDER BY 命名参数 0）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
