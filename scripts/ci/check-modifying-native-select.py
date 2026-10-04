#!/usr/bin/env python3
"""void @Modifying + 原生 SELECT 必炸组合机检（pre-commit 第七道，AGENTS §4 元规则）。

背景：pg_advisory_xact_lock 首版被写成 `@Modifying void` + 原生 SELECT（CI docker-it
实炸 7 例："A result was returned when none was expected"——@Modifying 走
executeUpdate，驱动拒收任何返回结果集的语句）。void 返回的 @Modifying 方法没有任何
东西能消费结果集，配 SELECT 属于**确定性的静默地雷**：本机 slice（mock EM）探不到、
编译期不报错，只在真 PG 上红——正是"踩坑一次即配同批门禁"的适用形态。

规则（保守，只拦可证明的错误）：
  1. 违规（exit 1）：方法同时满足 ①带 @Modifying ②返回 void ③@Query 是
     nativeQuery 且 SQL 以 SELECT 开头（可带 `(` 与换行）。void 消费不了结果集，
     该组合无合法场景。
  2. 提醒（不阻断）：@Modifying + 非 void + 原生 SELECT——有返回类型时可能确实要
     消费结果，交 review 判断。
  3. 正确写法：void 语义的锁/通知类 PG 函数走 EntityManager.createNativeQuery(...)
     .getResultList()（见 CheckinService.lockUserDay 的注释）。

退出码：0 = 无违规；1 = 有违规；2 = 源目录缺失（配置错误与违规分离，同族口径）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

MAIN_SRC = "annona-server/src/main/java"

# 逐行结构解析（不用跨行大正则：同串里字面量关键词与回溯会污染捕获，自测实证）：
# @Modifying 之后、方法声明之前的注解行聚合进 buf；命中“返回类型 方法名(”即结算。
QUERY_START = re.compile(r"@(Query|Modifying)\b")
DECL = re.compile(r"^\s*(void|Integer|int|Long|long|boolean|Boolean|List<.+?>|Set<.+?>|[A-Z]\w*)"
                  r"\s+([A-Za-z_]\w*)\s*\(")
NATIVE_TRUE = re.compile(r"nativeQuery\s*=\s*true")


def inspect(text: str) -> list[tuple[int, str, str]]:
    """返回带 @Modifying 的声明列表 [(起始行号, 返回类型, 注解块文本)]。

    从 @Query/@Modifying 起累积到方法声明行为止（续行/空行都入块）；宁可宽聚合，
    违规判定交给 scan 里的 @Modifying+nativeQuery+SELECT 三重条件，不误伤也不漏判。
    """
    hits: list[tuple[int, str, str]] = []
    buf: list[str] = []
    started_at = 0
    for idx, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if QUERY_START.match(line):
            if not buf:
                started_at = idx
            buf.append(line)
            continue
        d = DECL.match(raw)
        if d and buf:
            hits.append((started_at, d.group(1), "\n".join(buf)))
            buf = []
        elif buf:
            buf.append(line)
    return hits


def scan(repo_root: Path) -> tuple[list[str], list[str]]:
    violations: list[str] = []
    notices: list[str] = []
    src = repo_root / MAIN_SRC
    for path in sorted(src.rglob("*Repository.java")):
        text = path.read_text(encoding="utf-8")
        for line_no, ret_type, block in inspect(text):
            if "@Query" not in block or not NATIVE_TRUE.search(block):
                continue
            sql = " ".join(re.findall(r'"([^"]*)"', block))
            if not sql.lstrip().upper().startswith("SELECT"):
                continue
            rel = path.relative_to(repo_root).as_posix()
            if ret_type == "void":
                violations.append(f"{rel}:{line_no}: void @Modifying + 原生 SELECT")
            elif not re.search(r"for\s+update", sql, re.I):
                # SELECT ... FOR UPDATE 是 @Modifying 消费更新行的合法惯用法
                # （全仓先例：锁行预取/条件更新返新值），不算提醒面
                notices.append(f"{rel}:{line_no}: 非 void @Modifying + 原生 SELECT（确认是否需要 @Modifying）")
    return violations, notices


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    if not (repo_root / MAIN_SRC).is_dir():
        print(f"check-modifying-native-select: 源目录缺失 {MAIN_SRC}", file=sys.stderr)
        return 2
    violations, notices = scan(repo_root)
    for note in notices:
        print(f"check-modifying-native-select: 提醒 {note}")
    if violations:
        print("check-modifying-native-select: @Modifying 走 executeUpdate，"
              "void 方法无法消费结果集，原生 SELECT 必被驱动拒绝（CI docker-it 实测）：",
              file=sys.stderr)
        for v in violations:
            print(f"  {v}", file=sys.stderr)
        print("改法：EntityManager.createNativeQuery(...).getResultList()"
              "（先例 CheckinService.lockUserDay）。", file=sys.stderr)
        return 1
    print("check-modifying-native-select：通过（void @Modifying × 原生 SELECT 组合为 0）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
