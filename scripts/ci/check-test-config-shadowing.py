#!/usr/bin/env python3
"""检查 src/test/resources 不得定义与 main 同名的 Spring profile 配置文件。

Spring Boot 加载 application-{profile}.yaml 时，classpath 上只取**第一个**命中的
资源——test 类路径排在 main 之前，测试侧的同名文件会把 main 的整个 profile 配置
（KEK 映射、datasource……）静默遮蔽。P1a-05 实测：test 侧新建 application-docker.yaml
后，docker-it 全部 27 个上下文死于 KEK 校验（复刻了 B5 事故）。

规则：src/test/resources 下禁止出现 main resources 已有的 application*.yaml 文件名
（application-test.yaml 例外——main 没有它，且它本来就是测试专属 profile）。
"""

import sys
from pathlib import Path

TEST_RES = Path("annona-server/src/test/resources")
MAIN_RES = Path("annona-server/src/main/resources")

EXEMPT = {"application-test.yaml"}


def main() -> int:
    if not TEST_RES.is_dir():
        return 0
    main_files = {p.name for p in MAIN_RES.glob("application*.yaml")}
    problems = []
    for p in TEST_RES.glob("application*.yaml"):
        if p.name in EXEMPT:
            continue
        if p.name in main_files:
            problems.append(p)
    if problems:
        print("pre-commit: src/test/resources 定义了与 main 同名的 profile 配置文件，"
              "会遮蔽 main 的整个 profile（KEK/datasource 全部失效）：", file=sys.stderr)
        for p in problems:
            print(f"  {p.as_posix()}", file=sys.stderr)
        print("把这些键合并进 main 的对应 profile 文件，用环境变量区分测试/运行时行为。",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
