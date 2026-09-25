# architecture/ 索引

本目录记录**模块级架构**，随代码维护：改了模块的内部结构或对外契约，必须同步改这里。

判定标准：一个模块若能从包名 + `package-info.java` 在 5 分钟内读懂，就不需要文档；反之必须有。

| 模块 | 文档 | 为什么需要单独写 | 状态 |
|---|---|---|---|
| — | [overview.md](./overview.md) | 全局分层、依赖方向、启动与请求生命周期 | ✓ 已写 |
| `planner` | planner.md | 掌握度公式、规则链、保护规则，纯算法且是产品灵魂 | 待实现期写 |
| `evaluation` | evaluation.md | 分批/重试/汇总/降级四段流程 + 可比性判定 | 待实现期写 |
| `retrieval` | retrieval.md | 双通道 + RRF + 重排 + 降级顺序，参数耦合多 | 待实现期写 |
| `knowledge` | knowledge.md | ETL 状态机与幂等策略 | 待实现期写 |
| `interview` | interview.md | 组卷流程与 SKILL 加载机制 | 待实现期写 |
| `questionbank` | questionbank.md | 出题与容量校验、难度标定 | 待实现期写 |
| `voice` | voice.md | 音频链路时序、并发 TTS 调度、延迟预算 | 待实现期写 |
| `study` | study.md | 心跳→会话聚合→质量分级判定 | 待实现期写 |
| `usage` | usage.md | 记账不进事务的取舍、配额熔断点 | 待实现期写 |
| `agent` | agent.md | 工具注册、审批门、留痕模型 | P4 前写 |
| `schedule` `plan` `qa` `resume` `notify` `identity` | — | 常规 CRUD + 单层编排，读代码即可 | 不需要 |

## 单篇文档的固定结构

```markdown
# <module> 模块架构
1. 职责边界（一句话 + 明确不做什么）
2. 内部包/类协作图（Mermaid 或 ASCII）
3. 关键流程时序
4. 对外契约（暴露的接口 / 消费的事件 / 依赖的 SPI）
5. 数据模型要点与不变量
6. 已知取舍与技术债
```
