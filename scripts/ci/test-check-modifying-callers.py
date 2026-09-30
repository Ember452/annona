#!/usr/bin/env python3
"""check-modifying-callers.py 的可证伪自测（AGENTS §4 元规则：门禁必须能被证伪）。

三个用例全过才 exit 0：
  1. service 裸调 xxxRepository.modifyingMethod() 且无事务标记 → 返回码 == 1 且点名文件；
  2. 给同一文件加 @Transactional → 返回码 == 0；
  3. 源目录不存在 → 返回码 == 2（配置错误与违规分离）。
另附用例 4：只读方法（非 @Modifying）即使无事务也不误报。

运行：python scripts/ci/test-check-modifying-callers.py
"""

from __future__ import annotations

import io
import tempfile
from contextlib import redirect_stderr, redirect_stdout
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

_spec = spec_from_file_location(
    "check_modifying_callers",
    Path(__file__).with_name("check-modifying-callers.py"))
_mod = module_from_spec(_spec)
_spec.loader.exec_module(_mod)
check = _mod.check

REPO_IFACE = """package x;
import org.springframework.data.jpa.repository.Modifying;
public interface TaskRepository {
    @Modifying
    int resetForRetry(String id);

    java.util.List<String> findStale(String status);
}
"""

SERVICE_NO_TX = """package x;
public class RecoveryScheduler {{
    private final TaskRepository taskRepository;
    public void recover() {{
        taskRepository.resetForRetry("id-1");
        taskRepository.findStale("QUEUED");
    }}
}}
"""

SERVICE_WITH_TX = """package x;
import org.springframework.transaction.annotation.Transactional;
public class RecoveryScheduler {{
    private final TaskRepository taskRepository;
    @Transactional
    public void recover() {{
        taskRepository.resetForRetry("id-1");
    }}
}}
"""


def run_check(main_src: str) -> tuple[int, str]:
    err = io.StringIO()
    with redirect_stdout(io.StringIO()), redirect_stderr(err):
        code = check(main_src)
    return code, err.getvalue()


def write_tree(tmp: Path, service: str) -> Path:
    src = Path(tmp, "main", "java")
    (src / "x").mkdir(parents=True)
    (src / "x" / "TaskRepository.java").write_text(REPO_IFACE, encoding="utf-8")
    (src / "x" / "RecoveryScheduler.java").write_text(service, encoding="utf-8")
    return src


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        # 用例 1：裸调 @Modifying 且无事务 → 1，点名文件
        src = write_tree(Path(tmp), SERVICE_NO_TX)
        code, err = run_check(str(src))
        assert code == 1, f"用例1 期望 1，得到 {code}"
        assert "resetForRetry" in err and "RecoveryScheduler" in err, \
            f"用例1 stderr 应点名调用与文件，得到：{err}"
        assert "findStale" not in err, f"用例4 只读方法不应误报，得到：{err}"
        print("PASS 用例1：裸调 @Modifying 无事务 → exit 1 且点名（只读方法不误报）")

        # 用例 2：加 @Transactional → 0
        src2 = write_tree(Path(tmp, "withtx"), SERVICE_WITH_TX)
        code, _ = run_check(str(src2))
        assert code == 0, f"用例2 期望 0，得到 {code}"
        print("PASS 用例2：调用点有 @Transactional → exit 0")

        # 用例 3：源目录不存在 → 2
        code, _ = run_check(str(Path(tmp, "nowhere")))
        assert code == 2, f"用例3 期望 2，得到 {code}"
        print("PASS 用例3：源目录缺失 → exit 2（配置错误与违规分离）")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
