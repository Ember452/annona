#!/usr/bin/env python3
""">@Modifying 调用点事务覆盖机检（pre-commit 第六道，AGENTS §4 约定→机检元规则）。

背景：Spring Data 的 @Modifying 查询方法（UPDATE/DELETE/native INSERT）在**无活动事务**
的调用线程上直接抛 TransactionRequiredException——批 2 CI 实炸（Facade.answer），且
UsageRecorder 的异步 INSERT 属"静默丢账型"同源地雷（本机 slice 用 mock EM 探不到，
只有真 PG/真异步暴露）。仓库既有 pitfall 记忆，但"下一个人读文档"不是门禁。

规则（保守、可证伪，宁可漏报不误伤存量）：
  1. 扫全部 *Repository.java 接口，收集 @Modifying 注解紧跟的方法名集合（改方法签名
     才刷新集合，注释里提方法名不进入）。
  2. 扫 annona-server main 源里的每个 .java（跳过 Repository 接口本身），找**以
     xxxRepository 为接收者**直接调用上述方法的调用点。
  3. 命中调用点的文件必须含事务覆盖标记之一，否则记违规。
     标记：@Transactional / TransactionTemplate / executeWithoutResult / .execute(
     / PlatformTransactionManager（后者构造 TransactionTemplate 的前置）。

退出码：0 = 全部调用点有事务覆盖；1 = 有裸调 @Modifying 的调用点；2 = 源目录缺失
（配置错误与违规分离，同 check-migration-inventory 口径）。

局限（如实）：类级启发式，判"文件里有没有事务机制"而非"这一行的动态事务态"——跨类
委托（Facade→StateService，事务在下游 @Transactional 方法上）会让**上游**文件不含
标记但仍被算作调用点吗？不会：调用 @Modifying 的接收者是 repository 字段，只出现在
真正持有 repository 的那个类，委托链上游不直接碰 repository，故不误报。真漏报场景：
把 repository 赋给不以 Repository 结尾的变量再调用——命名约定已排除（全仓字段 xxxRepository）。
静态判不了事务传播，真炸语义仍由 docker 组 @Modifying 集测兜底（双防）。

自测见 test-check-modifying-callers.py。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

MAIN_SRC = "annona-server/src/main/java"

# @Modifying 紧跟（可有注解行/空白）的方法返回类型 + 方法名捕获
MODIFYING_DECL = re.compile(
    r"@Modifying\b[^;{]*?\b(?:int|long|void|boolean|Integer|Long)\s+([A-Za-z_]\w*)\s*\(",
    re.S)
# 调用点：以 xxxRepository（大小写不敏感的 repository 结尾标识符）为接收者调用某方法
CALL_TMPL = r"\b[A-Za-z0-9_]*[Rr]epository\s*\.\s*{name}\s*\("

TX_TOKENS = ("@Transactional", "TransactionTemplate", "executeWithoutResult",
             ".execute(", "PlatformTransactionManager")


def collecting_modifying_names(repo_root: Path) -> set[str]:
    names: set[str] = set()
    for path in repo_root.rglob("*Repository.java"):
        text = path.read_text(encoding="utf-8", errors="replace")
        if "@Modifying" not in text:
            continue
        names.update(MODIFYING_DECL.findall(text))
    return names


def check(main_src: str) -> int:
    root = Path(main_src)
    if not root.is_dir():
        print(f"check-modifying-callers: 源目录不存在：{root}", file=sys.stderr)
        return 2

    names = collecting_modifying_names(root)
    if not names:
        # 没有 @Modifying 方法时不视为错误（可能是新仓骨架），但要显式说明
        print("check-modifying-callers：未发现 @Modifying 方法，跳过（0 调用点）。")
        return 0

    patterns = {n: re.compile(CALL_TMPL.format(name=re.escape(n))) for n in names}
    violations: list[str] = []
    for path in root.rglob("*.java"):
        if path.name.endswith("Repository.java"):
            continue  # 接口本身是声明处，不是调用处
        text = path.read_text(encoding="utf-8", errors="replace")
        hit_methods = {n for n, p in patterns.items() if p.search(text)}
        if not hit_methods:
            continue
        if any(tok in text for tok in TX_TOKENS):
            continue  # 文件里有事务机制，放行
        rel = path.as_posix()
        for m in sorted(hit_methods):
            violations.append(f"  ✗ {rel}: 调用 @Modifying {m}() 但文件无事务标记")

    if violations:
        print("check-modifying-callers：以下 @Modifying 调用点所在类无事务覆盖"
              "（TransactionRequiredException 型地雷，批 2 实炸）：", file=sys.stderr)
        for v in violations:
            print(v, file=sys.stderr)
        print("修法：调用点包进 @Transactional 方法或 TransactionTemplate.execute*；"
              "确为设计使然再评估本门禁（勿 --no-verify 绕过）。", file=sys.stderr)
        return 1

    print(f"check-modifying-callers：通过（@Modifying 方法 {len(names)} 个，"
          f"调用点文件全部有事务覆盖）。")
    return 0


if __name__ == "__main__":
    args = sys.argv[1:]
    raise SystemExit(check(args[0] if args else MAIN_SRC))
