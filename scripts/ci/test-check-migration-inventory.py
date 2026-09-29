#!/usr/bin/env python3
"""check-migration-inventory.py 的可证伪自测（AGENTS §4 约定→机检元规则：门禁必须能被证伪）。

三个用例全过才 exit 0：
  1. 迁移里有 CREATE TABLE x_tmp 而基线清单未登记 → 返回码 == 1，且 stderr 点名 x_tmp；
  2. 把 "x_tmp" 登记进假清单 → 返回码 == 0；
  3. 迁移目录路径不存在 → 返回码 == 2（配置错误与违规是两种信号，不混用）。

运行：python scripts/ci/test-check-migration-inventory.py
"""

from __future__ import annotations

import io
import tempfile
from contextlib import redirect_stderr, redirect_stdout
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

# 被检文件名带连字符，不是合法模块名，按路径加载
_spec = spec_from_file_location(
    "check_migration_inventory",
    Path(__file__).with_name("check-migration-inventory.py"))
_mod = module_from_spec(_spec)
_spec.loader.exec_module(_mod)
check = _mod.check

FAKE_IT = """class FlywayBaselineIT {{
    assertThat(tables).containsExactlyInAnyOrder(
        "app_user", "direction"{extra});
}}
"""


def run_check(migration_dir: str, it_file: str) -> tuple[int, str]:
    err = io.StringIO()
    with redirect_stdout(io.StringIO()), redirect_stderr(err):
        code = check(migration_dir, it_file)
    return code, err.getvalue()


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        mdir = Path(tmp, "migration")
        mdir.mkdir()
        (mdir / "V1__baseline.sql").write_text(
            "CREATE TABLE app_user (id UUID PRIMARY KEY);\n"
            "CREATE TABLE direction (id UUID PRIMARY KEY);\n", encoding="utf-8")
        it = Path(tmp, "FlywayBaselineIT.java")
        it.write_text(FAKE_IT.format(extra=""), encoding="utf-8")

        # 用例 1：新迁移建表未登记 → 1，且点名
        (mdir / "V9__fake.sql").write_text(
            "-- 注释里的 CREATE TABLE ghost_tbl 不得误报\n"
            "CREATE TABLE x_tmp (id UUID PRIMARY KEY);\n", encoding="utf-8")
        code, err = run_check(str(mdir), str(it))
        assert code == 1, f"用例1 期望 1，得到 {code}"
        assert "x_tmp" in err, f"用例1 stderr 应点名 x_tmp，得到：{err}"
        assert "ghost_tbl" not in err, f"用例1 注释行不应误报，得到：{err}"
        print("PASS 用例1：未登记新表 → exit 1 且 stderr 点名")

        # 用例 2：登记后 → 0
        it.write_text(FAKE_IT.format(extra=', "x_tmp"'), encoding="utf-8")
        code, _ = run_check(str(mdir), str(it))
        assert code == 0, f"用例2 期望 0，得到 {code}"
        print("PASS 用例2：登记后 → exit 0")

        # 用例 3：迁移目录不存在 → 2
        code, _ = run_check(str(Path(tmp, "nowhere")), str(it))
        assert code == 2, f"用例3 期望 2，得到 {code}"
        print("PASS 用例3：路径缺失 → exit 2（配置错误与违规分离）")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
