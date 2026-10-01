# 决策 A/B — 决策选题 vs 随机选题

对照实验工具（P1c-08，设计文档 §6.5"拒绝 n=1 伪统计"）。

## 为什么不在本机纯模拟

§6.5 明确要"固定题库 + 固定模型 + 固定行为轨迹"跑真实两条策略并公布数字，而不是在 UI 上
画开启前后对比。纯内存模拟的得分模型参数可以被人为调成任意结论——那与 n=1 伪统计无异。
因此 `ab.py` 驱动**真实部署实例**（真实 advisor 选题、真实 decision_trace、真实组卷），
本机不跑（无 Docker/PG）。

## 运行（部署/CI）

1. 起一个开了 demo 历史的实例：`--annona.demo.enabled=true`（`DemoSeedRunner` 灌 6 周历史）。
2. 用 demo 账号登录取会话 token。
3. `python scripts/ab-decision/ab.py --direction <demo方向id> --sessions 8 --total-count 5`
4. 脚本打印 decision(auto) vs random(manual) 两组的选题留痕统计。

## 产出

把实测数字与结论写进 `docs/benchmarks/决策A-B_<日期>.md`。**若决策组不优于随机组，必须公开写进
README 并说明修正方向，不许藏着**（开发计划维护规则 5 / P1 出口④）。

## 文件

- `ab.py`：实验驱动与度量（对 `planMode=auto` vs `planMode=manual` 各跑 N 场，读 decision_trace 聚合）。
