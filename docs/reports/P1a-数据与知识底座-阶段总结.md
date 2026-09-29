# P1a 数据与知识底座 阶段总结

> **状态：草稿 · 待用户定稿**。按用户指示在出口③未闭合、DOCX 用例 B 跳过的前提下先行起草；所有未闭合项在本总结内如实标注为遗留，不粉饰。
- 日期：2026-09-28 起草（阶段窗口 2026-09-25 ~ 2026-09-28，4 个日历日）
- 对应里程碑：设计文档 §14 P1a（数据与知识底座）
- 实际耗时：4 个日历日；全仓 206 commit（09-25 起 54 → 09-26 42 → 09-27 61 → 09-28 49），P1a 四批 + 三轮外审修复 + 09-27 假绿复盘中修为主体。计划工作量 14.5 人日（含 09b），本总结不做等效人日折算（无工时记录）。

## 1. 目标 vs 实际

| 任务 | 计划 | 实际 | 证据锚点 |
|---|---|---|---|
| P1a-00 前端设计基座 | shadcn/ui 基础件 + AppLayout 视觉壳 | ✅ 完成 | P1a 后续页面零迁移复用 |
| P1a-01 identity 模块 | 注册/登录/会话/锁定/改密 | ✅ 完成（含 09-27 加固批：refresh 游离实体 ×3、CI 门禁假绿修复） | [reports/2026-09-27-docker-it-假绿复盘.md](../reports/2026-09-27-docker-it-假绿复盘.md)、run 36293504920 六 job 全绿 |
| P1a-02 IdentityProvider 三模式 | local / platform / none | ✅ 完成 | identity-modes ADR；none 模式无登录直达 |
| P1a-03 direction 字典 | 字典服务 + 选择器 + 绑定知识库 | ✅ 完成 | direction-master-data ADR |
| P1a-04 study 采集 | 打卡/番茄钟/心跳质量分级 | ✅ 完成（VERIFIED/PARTIAL/SELF_REPORTED 有单测） | study-checkin-index ADR |
| P1a-05 knowledge 写侧 | 上传→S3→解析→分块→向量化→状态机 | ✅ 完成（含外审 P0/P1 修复：短事务化、S3 afterCommit、Tika 超时等） | KnowledgeIngestFlowIT、手工验收 A/C/D/E |
| P1a-06 分块器 | 纯逻辑 + golden + 85% 覆盖率 | ✅ 完成，批 3 又 🔄 修复边界缺陷（char-v2，见 §4） | ChunkerTest 23 条 + golden；`lication.properties` 缺陷闭环 |
| P1a-07 retrieval | 语义 + 关键词 + RRF 全含后端 | ✅ 完成 | retrieval-hybrid ADR；V5 生成列/pg_trgm CI 坐实 |
| P1a-08 流式问答 | SSE 流式 + 会话 + 引用追溯 + 净化渲染 | ✅ 代码与 CI 完成；⚠️ 真模型人工 demo 未跑；🔄 外审修复批（V7 miss_reason、池饱和 1100、并发序号钉） | QaFlowIT 5 用例（真 PG）；PR #13 已合并 + fix 分支待 CI |
| P1a-09 检索评测 | Recall@K / MRR 基线出报告 | ⚠️ 部分：工具链与基线已出，但 **Recall@3 = 1.0000 天花板饱和、无分辨力** | [benchmarks/检索基线_20260928.md](../benchmarks/检索基线_20260928.md) |
| P1a-09b 评测集提难度 | 语料 15-20 篇、query ≥60 | ✅ 完成（09-29 续批收口，见 §6 更新） | [benchmarks/检索基线_20260928.md](../benchmarks/检索基线_20260928.md) Run 3 |
| 🔄 计划外 | — | 分块边界缺陷修复（char-v2）、V7 miss_reason、AGENTS §4 跨模块只读消歧、dockerless 拓扑教程、09-27 假绿复盘机制 | 各 ADR / 复盘报告 |

**出口条件对账**：① 真实资料入库后可流式问答并显示引用——⚠️ 代码与 CI 具备，真模型人工 demo 未跑；② 心跳质量分级单测+实测——✅；③ 混合检索相对纯向量的实测提升——✅ 闭合（09-29 Run 3：`BOTH` 0.9958 vs `SEMANTIC` 0.9625，R@3 +3.3pp、判别题定向 rescue ×2；见基线文档 Run 3）；④ chunk 包覆盖率达标——✅（JaCoCo CLASS 级 85% 机检进 verify）；⑤ 阶段总结已写——✅（本文档，待定稿）。

## 2. 本阶段产出的能力（用户视角）

- **身份**：三种部署模式一套业务代码——local（邮箱注册/登录/改密/10 次失败锁定）、platform（受信反代头 JIT 建号）、none（单机直用）。
- **方向字典**：内置技术方向 + 用户自定义方向，可归档不可误删，可绑定知识库文档。
- **自习室**：番茄钟与打卡采集，服务端心跳质量分级（挂机 30 分钟自动降级 PARTIAL，手动补录 SELF_REPORTED 不进决策）。
- **知识库**：上传 PDF/DOCX/TXT/MD（≤50MB）→ S3 → 解析 → 结构感知分块（标题路径 + 原文偏移，可逐块预览校验）→ 向量化 → 就绪；同用户重复上传秒级幂等零消耗；失败自动恢复与手动重建；分块算法升级（char-v1→v2）时界面提示并一键重建。
- **检索**：`POST /api/retrieval/query` 语义（HNSW）+ 关键词（tsv/pg_trgm）双通道 RRF 融合，空命中返回"凭什么没找到"的诊断（无就绪文档 / 模型身份不匹配 / 真无匹配）。
- **问答**：`/qa` 页一次提问逐字输出（SSE 四事件），回答带编号引用、点击角标跳回原文分块；空命中显示一行人话原因且随历史持久化；断线保留已生成部分；会话列表与历史完整；模型未配置/服务繁忙给出可重试的业务错误。
- **评测**：`scripts/rag-eval` 一键出 Recall@K / MRR / 延迟报告，`retrieval_eval_run` 留痕可复跑对照。

## 3. 验收证据

**命令与结果（本机 Windows，Java 21；均实跑）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 后端全量 | `./mvnw.cmd -B -q verify` | EXIT=0（单测+slice+ArchUnit 8 条+JaCoCo 60% BUNDLE 与 chunk 包 85% CLASS 机检；docker 组本机排除） |
| 分块边界 | `mvnw test -Dtest='ChunkerTest,ChunkerGoldenTest'` | 23/23（golden 重写 + 超长原子串硬切 + "原子串至少在一片完整可见"不变量） |
| qa 编排 | `mvnw test -Dtest='QaServiceTest'` | 11/11（占位→条件回填、断线保留、引用序、池饱和 1100、跨用户 2500） |
| chat 实现 | `mvnw test -Dtest='OpenAi*Test,ChatRoutingTest'` | 全绿（SSE 解码器表驱动 + JDK HttpServer 真连接：非 2xx→1104、断流→1105、三态路由） |
| 前端五连 | `pnpm typecheck / lint / test / build` | 全 EXIT=0，vitest 33/33（含 XSS 构造面断言与 mock DEV 门行为断言），产物直写 server static/ |

**CI（docker-it 等本机跑不到的，由 runner 背书）**：P1a-04 窗口 run [36293504920](https://github.com/Ember452/annona/actions/runs/36293504920) 六 job 全绿；批 2 CI 绿（V5 生成列 / pg_trgm / eval 环境三个 SQL 未知数坐实）；批 3 经 PR #13 合并入 main；首轮 CI 曾 1 失败（`FlywayBaselineIT` 未登记 V6 表——该测试注释里记录了 V2/V4/V6 三次同型踩坑），修复后重推；外审修复批（fix/p1a-08-review-fixes）PR CI 收口中。

**评测（P1a-09，真实数字）**：qwen3.7-text-embedding，20 query / 5 篇语料 / K=3，`retrieval_eval_run` 三行留痕——`BOTH` Recall@3 1.0000 / MRR@3 0.9667 / P50 202ms；`SEMANTIC` 1.0000 / 0.9417 / 178ms；`KEYWORD` 0.9500 / 0.9000 / ≈0ms。结论：**两档天花板饱和，无差值可判读**——混合检索的价值主张本轮未获实证（§6）。

**手工验收（真文件 + 真模型，VM 中间件拓扑）**：批 1 教程 A/C/D/E 闭（真 PDF/MD 入库、重复上传幂等、分块预览、重建）；**B 用例（真 DOCX）经用户决定跳过**；分块边界修复后"真 PDF 重建 + psql 抽查首行"的真库证据**未采集**（单测/golden 层已覆盖，见 §5）。

## 4. 与原设计的偏离（均已回写文档/ADR）

| 偏离 | 原因 | 回写 |
|---|---|---|
| 流式 chat 端口进 `annona-common` 而非 spi | 外审确认：spi 是唯一发 Central 的 artifact，冻结易变流式协议风险大于收益；无外部实现方需求 | qa-streaming-adr §决策 + ModelProvider javadoc |
| ChatConfig/ChatProperties 落 `infrastructure/llm` | 计划误写 server/config；embedding 先例在 infra，Surgical Changes 跟随仓库现实 | commit body 记录 |
| Markdown 净化换轨 | 借鉴地图原定对齐 🅢 sanitize schema；改为**不装 rehype-raw**、HTML 由构造不渲染，XSS 面关闭且免白名单维护 | qa-streaming-adr §决策 6 + 借鉴地图 08 行回写 |
| 跨模块只读首例（qa → retrieval/knowledge QueryService） | P1a-08 验收必须消费检索与分块正文；走 AGENTS 规定的只读路径 | qa-streaming-adr §决策 7 + **AGENTS §4 消歧**（外审修复批） |
| 错误码 2501 未预留 | 无按 id 查消息的端点，YAGNI | commit body |
| V7 miss_reason（计划外迁移） | 外审发现空命中诊断只在流式期可见 | qa-streaming-adr §后续修订 1 |
| 分块边界缺陷（计划外） | 真 PDF 实测块以 `lication.properties` 开头；首轮修复回退后重做（pull-back 语义 + 32 上限） | knowledge-ingestion-adr + golden + ChunkerTest |
| `spring-boot:run` 不可用 | reactor 执行模型问题（2026-09-28 实测） | AGENTS §8.3 + 手工验收教程 §6 |

## 5. 已知缺陷与技术债（影响 / 优先级 / 处理触发条件）

| # | 债 | 影响 | 触发条件（何时处理） |
|---|---|---|---|
| D1 | jieba 1.0.2（2017 年冻结依赖）；且换分词/重刷 tokens 只有"逐文档重建"一条路，无批量工具 | 分词质量天花板 + 大语料重建慢 | 语料量级上万、或 P1b 出题质量指向分词；届时先建批量重刷（revectorize 的批量化） |
| D2 | ai-io 池（8/32/200）被流式问答与 embedding/向量化共享，一次回答占线程至上游吐完 | 并发问答排队（用户侧"转圈"），极端时挤占向量化 | Micrometer `executor.queued`（ai-io）持续非零或真实并发投诉；届时 yaml 调参或拆池（已有配置面，零返工） |
| D3 | `miss_reason` CHECK 把诊断枚举名写进 DDL | 每新增一种诊断 = 一次迁移 | 诊断种类扩展时随枚举同步（qa-streaming-adr §后续修订 1） |
| D4 | `EMITTER_TIMEOUT_MS=120s` 常数 | 本地冷启动模型（Ollama/LM Studio）首 token 超 120s 会被掐流 | 真要本地模型演示时提为配置或调大（一行） |
| D5 | QA→检索为同步直调、无事件解耦 | 检索抖动直接传导到问答首字延迟 | 检索需要异步化/降级时（读路径刻意为之，非债性违规） |
| D6 | 分块边界修复的真库证据未采集（单测/golden 已覆盖） | "修复在真 PDF 上生效"缺一手证据 | 下次 VM 验收窗口顺手补（重建后 `select left(content,60)` 抽查） |

## 6. 遗留问题进入下一阶段（P1b 前 / P1b 中）

| 遗留 | 必须在 P1b 开工前解决吗 | 说明 |
|---|---|---|
| **P1a-09b（评测集提难度重跑）** | ~~是——P1b 决策层消费检索语义之前~~ **✅ 已解决（09-29）** | Run 3 闭合出口③：`BOTH` 0.9958 vs `SEMANTIC` 0.9625（R@3 +3.3pp、MRR +3.6pp），判别题定向 rescue ×2（混合独占命中 n=2），近重复 4 篇经 0929 外审人工确认；详见基线文档 Run 3 |
| 真 chat 模型人工 demo（逐字输出 + 引用跳转 + 空命中提示） | 建议开工前补（10 分钟） | 出口①的人工证据；教程已备（含 dockerless 拓扑） |
| 外审修复批 PR CI 收口 | 合并前必须绿 | 分支已推送，描述已备 |
| DOCX 手工验收 B 用例 | 否（用户决定跳过） | 下次真实环境验收窗口顺手补 |

## 7. P1b 下一阶段入口条件（可验证门槛）

1. 主干 CI 全绿（含 V7 迁移与 QaFlowIT 5 用例）。
2. P1a-09b 结论已回写 [benchmarks/检索基线_20260928.md](../benchmarks/检索基线_20260928.md)：给出 `BOTH` vs `SEMANTIC` 可判读的差值，或明确"当前语料不可判读 + 已按设计加难度仍饱和"的结论。（✅ 2026-09-29 满足：Run 3 差值 R@3 +3.3pp + 混合独占命中 n=2）
3. 真 chat 模型人工 demo 走通一遍（逐字输出、引用跳块、空命中提示行、刷新历史完整）——即出口①的人工证据补齐。
4. chat 通道在真实 Key 下至少完成一次含引用的完整问答（同时为 P1b 面试评估复用 chat 层提供连通性背书）。

## 8. 借鉴使用记录（上游：🅖 interview-guide / 🅢 summer-checkin，均为自有项目）

| 批 | 实际扫了什么 | 借了什么机制 | 改掉的不适配 | 上游没有而自写的 |
|---|---|---|---|---|
| 批 1（05/06） | 🅖 上传/解析/状态机参数、🅢 chunk.ts + rag-chunk.test.ts、🅢 上传幂等 | 50MB 上限、Tika 超时取消、attempt_id fencing、滑窗核心与死循环兜底验收、hash 幂等 | 纯文本解析→结构化块 IR；裸 string[]→带偏移与标题路径 | 状态机条件 UPDATE 全套、分块预览 UI、revectorize |
| 批 2（07/09） | 🅢 raw SQL `$n::vector`、🅖 spring.ai.retry 口径、jieba 接法 | pgvector 字面量写法、不重试口径、双通道 SQL 形状 | 共享 vector_store 表→kb_doc_chunk 行内向量；Spring AI→JDK HttpClient + SPI | jieba 端口化（common/search）、RRF 纯函数 + 85% 机检、eval 留痕投影 |
| 批 3（08） | 🅖 RagChat{Controller,SessionService}、prompt 三件、🅢 chat 路由 + deepseek.ts + sanitize 测试 | 先落库再开流、completed 列 + 只取 completed 组上下文、标题前 20 字、注入防御 prompt、reader loop + abort | 裸文本流+\n 转义→JSON 四事件信封；Flux→SseEmitter+ai-io 池；sanitize schema→不装 rehype-raw | 结构化 citations（JSONB）、空命中诊断透传、SSE 解码器独立单测、引用跳块 |
| 外审修复批 | 本仓 knowledge 条件 UPDATE 惯用法 | 写竞争用条件语句而非乐观锁重试 | findById+save 回填在并发删除下崩 | —（纯修复） |

借鉴地图 08 行已按实测回写（借什么/必改点均已更新）；其余批次行在各自 commit body 留有四行说明。
