#!/usr/bin/env python3
"""迁移新建表 → FlywayBaselineIT 基线清单登记一致性机检（pre-commit 第五道）。

背景：FlywayBaselineIT 用 containsExactlyInAnyOrder 断言"全库表清单 == 期望清单"
（兼职守卫"没有迁移外的游离表"）。新增 CREATE TABLE 的迁移若不同步登记，CI docker
组必假红——V2/V4/V6 三次同型踩坑（AGENTS.md §4 约定→机检升级元规则第 5 例）。

规则：扫描 db/migration/V*__*.sql 里行首的 CREATE TABLE [IF NOT EXISTS] 表名
（行首锚定天然跳过 `-- 注释` 与正文提及），逐一核对 FlywayBaselineIT.java 是否已
登记；缺一个即返回 1。只改列/索引/约束的迁移（无 CREATE TABLE）天然通过。
DROP TABLE 不拦截，仅打印提醒（允许先落迁移再同步清单，由人工确认）。

退出码：0 = 全部已登记；1 = 有新表未登记；2 = 路径配置错误（迁移目录/基线文件缺失）。
自测见 test-check-migration-inventory.py（可证伪：未登记→1、登记后→0、路径缺失→2）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

MIGRATION_DIR = "annona-server/src/main/resources/db/migration"
BASELINE_IT = "annona-server/src/test/java/io/annona/integration/FlywayBaselineIT.java"

# 行首锚定（允前导空白）：CREATE TABLE / CREATE UNLOGGED TABLE / CREATE TEMP 系列不匹配
# 注释行；允许反引号/双引号包裹的表名。
CREATE_RE = re.compile(
    r"(?im)^\s*CREATE\s+(?:UNLOGGED\s+)?TABLE\s+"
    r"(?:IF\s+NOT\s+EXISTS\s+)?[\"`]?([A-Za-z_][A-Za-z0-9_]*)[\"`]?")
DROP_RE = re.compile(
    r"(?im)^\s*DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?[\"`]?([A-Za-z_][A-Za-z0-9_]*)[\"`]?")


def migration_files(migration_dir: Path) -> list[Path]:
    return sorted(p for p in migration_dir.glob("V*__*.sql"))


def check(migration_dir: str, baseline_it: str) -> int:
    """核心检查：返回 0/1/2（语义见模块 docstring 与自测）。"""
    mdir = Path(migration_dir)
    if not mdir.is_dir():
        print(f"check-migration-inventory: 迁移目录不存在：{mdir}", file=sys.stderr)
        return 2
    it_file = Path(baseline_it)
    if not it_file.is_file():
        print(f"check-migration-inventory: 基线断言文件不存在：{it_file}", file=sys.stderr)
        return 2

    created: list[str] = []
    dropped: list[str] = []
    for path in migration_files(mdir):
        content = path.read_text(encoding="utf-8")
        created.extend(CREATE_RE.findall(content))
        dropped.extend(DROP_RE.findall(content))
    created = list(dict.fromkeys(created))  # 去重保序

    inventory = it_file.read_text(encoding="utf-8").lower()
    missing = [t for t in created if t.lower() not in inventory]

    for t in dict.fromkeys(dropped):
        print(f"  提醒（不拦截）：{t} 被 DROP——确认基线清单是否需要同步移除")

    if missing:
        for t in missing:
            print(f"  ✗ {t}: 新建表未登记进 FlywayBaselineIT 全库清单断言"
                  f"（V2/V4/V6 三次同型假红）", file=sys.stderr)
        print(f"check-migration-inventory：{len(missing)} 处违规——"
              f"请在 {baseline_it} 的 containsExactlyInAnyOrder 登记后重试。", file=sys.stderr)
        return 1

    print(f"check-migration-inventory：通过（迁移 {len(migration_files(mdir))} 个，"
          f"建表 {len(created)} 张全部已登记）。")
    return 0


if __name__ == "__main__":
    # 可选参数覆盖路径（自测用）；缺省为仓根相对路径
    args = sys.argv[1:]
    raise SystemExit(check(args[0] if len(args) > 0 else MIGRATION_DIR,
                           args[1] if len(args) > 1 else BASELINE_IT))
