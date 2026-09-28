# P1a 人工收口清单（逐项完成后勾销）

> 这些事项需要真实环境（真 Key、真语料、VM 中间件栈）或人工判断，无法由 AI 代做。
> 全部完成 = P1a 真正闭环；完成一项就在对应位置勾销：P1b issue 的"批 0"勾选框、
> [阶段总结 §6](../reports/P1a-数据与知识底座-阶段总结.md)、开发计划对应行。

## 1. 建 P1b 阶段 issue（5 分钟）

正文已拟好（含批次进度勾选框、硬约束、节奏约定），**直接复制本文档附录 A** 粘贴到 GitHub issue。
建好后把 issue 链接回填到 [开发计划](../annona-开发计划.md) 顶部进度行。

## 2. 真模型人工 demo（约 10 分钟，出口①的人工证据）

前置：VM 起 `docker/docker-compose.dev.yml --profile s3`（拓扑见 [手工验收-知识库入库 §6](./手工验收-知识库入库.md)），
本机 `.env` 在已有 embedding 配置基础上补 chat 四键（模板见 `.env.example` 的 Chat 通道一节）：

```text
ANNONA_MODEL_CHAT_PROVIDER=openai-compatible
ANNONA_CHAT_BASE_URL=<你的 OpenAI 兼容端点>
ANNONA_CHAT_API_KEY=<Key>
ANNONA_DEFAULT_CHAT_MODEL=<模型 id>
```

步骤：打包 `.\mvnw.cmd -q -DskipTests package -pl annona-server -am` → `java -jar annona-server\target\annona-server.jar` →
知识库确认至少一篇 READY 文档 → 打开 `/qa` 页 → 按序检查：

| # | 动作 | 通过标准 |
|---|---|---|
| 1 | 问一个语料内问题 | 回答**逐字输出**（不是整段蹦出） |
| 2 | 点击回答下方引用角标 | 弹出分块预览并**滚动定位到对应块**（高亮） |
| 3 | 刷新页面再进会话 | 历史完整（含 miss_reason 提示行——若该回答曾空命中） |
| 4 | 问一个语料外问题 | 回答为"资料中没有相关内容"类文案 + 空命中原因行（NO_MATCH/NO_READY_DOC） |
| 5 | 回答中途刷新/断开 | 历史里保留已生成的部分内容并标"回答中断" |

记录：截图（1/2/4 各一张）贴进 P1b issue 的批 0 勾选评论；通过即勾销阶段总结出口①。

## 3. P1a-09b 评测集提难度重跑（0.5 天，出口③唯一路径）

工具已备齐（本批新增）：`scripts/rag-eval/queries-09b.template.json`（60 槽位预分配：symbol 15 / clause 15 / phrase 30）、
`scripts/rag-eval/validate-09b.py`（准入机检）、`compare.py` 新增**混合独占命中**汇总（BOTH 命中而 SEMANTIC 未命中——数量为 0 即无增益，P1a-09 首轮正是 0）。

| 步骤 | 动作 | 说明 |
|---|---|---|
| 1 | 收集真语料 15–20 篇放 `scripts/rag-eval/corpus-09b/` | **含 3–5 篇近重复**（同主题改写/同代码段换皮——近重复是人工判定项，机器查不了） |
| 2 | 复制模板为 `scripts/rag-eval/queries-09b.json` 并填写 | 每条 text 写真实问题、relevant_docs 填应命中的语料文件名；口径写在模板 `_readme` |
| 3 | `python scripts/rag-eval/validate-09b.py --queries scripts/rag-eval/queries-09b.json --corpus-dir scripts/rag-eval/corpus-09b` | 必须通过再烧 eval（P1a-09 教训：先机检再跑真模型） |
| 4 | 起 dev 栈与真 embedding Key，按 [指标测试-检索](./指标测试-检索.md) 跑 `eval.py`（`--queries`/`--corpus-dir` 指向 09b 文件，`--label 09b-<日期>`） | 一次运行产出 BOTH/SEMANTIC/KEYWORD 三档 |
| 5 | `python scripts/rag-eval/compare.py out/rag-eval/eval-09b-*.json`（只有一份时同路径传两次） | 看 Recall@3 差值 + **混合独占命中 n** |
| 6 | 结论回写 [benchmarks/检索基线_20260928.md](../benchmarks/检索基线_20260928.md)（**同文件追加，不新建第二份**），并注明"近重复 n 篇，人工确认" | 目标：纯向量 Recall@3 < 0.85 且两档出现可判读差值；若仍饱和 → 按计划继续加难度 |

**判定规则**（09b 行原文）：出现可判读差值 → 出口③闭合；`BOTH ≤ SEMANTIC` → 触发 chinese-keyword-search-adr 重评估分支，批 3 代码零返工。

## 4. DOCX 手工验收用例 B（可选，用户已决定延后）

[手工验收-知识库入库](./手工验收-知识库入库.md) 的 B 用例原样执行即可；与步骤 2 共用同一次环境，顺手做完成本≈0。

## 附录 A：P1b 阶段 issue 正文（复制粘贴）

```markdown
## 目标
两个方向能完成一场面试——内置技术方向、知识库派生方向；评估分数可比。
任务总览以开发计划 §P1b 为唯一真相源（P1b-01…10 + 出口条件①-⑥），本 issue 只追踪批次级进度，不复制任务表。

## 批次进度（随 PR 合并勾选）
- [ ] 批 0：P1a 收口——P1a-09b 评测结论回写基线文档；真模型人工 demo（逐字输出/引用跳转/空命中提示）
- [ ] 批 1（出题链，约 6d）：P1b-01 SKILL 注册表 + P1b-02 出题管道（复用 knowledge SSE 进度信封）+ P1b-03 容量校验 → 验收：一篇讲义生成 10 题带评分标准
- [ ] 批 2（面试链，约 5.5d）：P1b-04 组卷与去重 + P1b-05 状态机/中断续面 + P1b-10 计量与 Key 五用途 → 验收：完成一场面试，杀进程可续，token 成本可查
- [ ] 批 3（评估与交付，约 6.5d）：P1b-06 分批评估/降级 + P1b-07 可比性留痕 + P1b-08 简历 + P1b-09 PDF 导出 → 验收：两方向各一场面试出 PDF 报告，换模型趋势断开可复现

## 硬约束（开工前已核，2026-09-28）
- P1a-09b 结论必须先于批 2：组卷去重是向量+关键词双判，评测集当前无分辨力（Recall@3 双档 1.0000），不可在无结论状态下定去重阈值
- 跨模块只读走 QueryService（AGENTS §4 已消歧）；LLM/外部调用不进事务；异步出题复用 P1a-05 的 SSE 信封契约（加字段不改语义）
- 交卷幂等键 = session_id + evaluator_version（SingleFlight 只用于读合并）

## 节奏
每批一个 PR，批间外审；CI（含 docker-it）绿才合；借鉴说明随 feat commit 提交并回写借鉴地图。

## 挂账
- [ ] 计划头标注 14 人日 vs 任务行求和 18 人日，批 0 时重估（P1a 实际节奏 4 日历日/14.5 计划人日，供校准）
- [ ] P1a 遗留：分块边界真库证据抽查（下次验收窗口）、DOCX 用例 B（已决定延后）
- [ ] 出口条件核对以开发计划 §P1b 出口为准（6 条）
```
