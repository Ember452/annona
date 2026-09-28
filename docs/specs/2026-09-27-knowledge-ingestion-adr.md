# ADR: 知识入库管线——结构化块 IR 为轴，kb_doc/kb_doc_chunk 落 V4，EmbeddingProvider 为独立 SPI 扩展点

- 日期 / 状态：2026-09-27 / Accepted
- 相关文档：[storage-single-postgres-adr](./2026-09-25-storage-single-postgres-adr.md) · [s3-storage-silo-adr](./2026-09-26-s3-storage-silo-adr.md) · [direction-master-data-adr](./2026-09-25-direction-master-data-adr.md) · [model-api-key-adr](./2026-09-25-model-api-key-adr.md) · [dockerless-local-dev-adr](./2026-09-25-dockerless-local-dev-adr.md) · 开发计划 P1a-05/06

## 背景

P1a-05（入库管线）与 P1a-06（分块器）是 P1a 后半段（检索 / 问答 / 评测）的地基。两个上游给了相反的参照：🅖 interview-guide 解析输出**纯文本**、分块藏在 Spring AI `TokenTextSplitter` 里、向量存共享 `vector_store` 表靠 metadata JSON 过滤；🅢 summer-checkin 分块输出裸 `string[]`，无偏移无结构。而 annona 的既定需求（P1a-08 引用跳回原文段落、P1a-07 关键词通道标题加权、`analyzer_version` 升级只重分块不重解析、SPI `RetrievalHit` 注释已承诺 `chunkId 对应 kb_doc_chunk.id`）都要求**结构化与可定位**，上游形态撑不住。向量维度、状态机并发语义（多实例领取、超时回收、终态保护）需要在写第一行迁移 SQL 之前定死——这是返工代价指数级的结构层。

## 决策（祈使句）

1. **解析→分块以结构化块 IR 为契约**。`annona-common` 新增 `parse` 包：`DocumentParser` 端口 + `DocumentBlock` record（type ∈ HEADING(level)/PARAGRAPH/LIST_ITEM/TABLE/CODE，text，charStart/charEnd 源文本偏移）。分块器只吃 IR，不碰文件、不碰解析器。
2. **表名与迁移**：`kb_doc` / `kb_doc_chunk` 建在 **V4**（V3 已被 study 索引占用）；V4 同时兑现 V1 预留的 `fk_direction_kb_doc` 外键。表名不可再改（SPI DTO 注释已钉死）。
3. **状态机**：`PENDING → PARSING → CHUNKING → EMBEDDING → READY | FAILED` 六态，全部走**条件 UPDATE**（`WHERE status=期望态 AND (attempt_id IS NULL OR attempt_id=本次)`），`attempt_id` 作执行代次 fencing；心跳 30s 节流、恢复调度 pending 10m / processing 15m / 间隔 60s / 上限 3 次（借 🅖 实测参数）。禁止无条件 `setStatus`。
4. **分块参数**（借 🅢，按字符计）：节内软上限 800、窗口重叠 50、句末断点位置 ≥60%、`advance = max(len − overlap, 1)` 三重死循环兜底。无标题文档整文单节，同样走窗口逻辑。
5. **EmbeddingProvider 为独立 SPI 扩展点**（`spi/model` 新增接口，`ModelProvider` javadoc 已预留此演进）：`name() / dimensions() / embed(List<String>)`。infrastructure 落 OpenAI 兼容实现（`/embeddings`，批量 ≤10，借 DashScope 上限），Key 走 env 注入；`spi/fake` 落确定性 Fake 供本机与 CI。
6. **向量版本口径**：`kb_doc.embedding_model` 标记本 doc 向量所属模型；检索端只服务 `embedding_model` 等于当前配置的 READY 文档。**多版本向量并存表不做**。
7. **重嵌入为重建式**：re-vectorize 清空该 doc 分块重做，失败即 FAILED（原文与 S3 对象无损，重跑即恢复）。
8. **端口归属**：`DocumentParser` / `ObjectStorage` / `TaskStreamPort` 端口声明在 **annona-common**（SessionStore/HeartbeatTimeline 先例），Tika / S3(AWS SDK v2, forcePathStyle) / Redisson 实现在 **annona-infrastructure**；`modules/knowledge` 只依赖端口（ArchUnit 已有规则兜底）。
9. **启动不 fail-fast embedding Key**：缺 Key / `provider=none` 时应用正常起，入库与向量化时报 knowledge 段错误（2300–2399）。StartupValidator 只管 KEK，不扩。
10. **前端**：知识库为**独立入口** `/knowledge`（2026-09-27 用户拍板，覆盖"仅作为问答后端"的原设想）；进度用 SSE（信封 `{stage, processed, total, state, message}`，此信封是 P1b-02 出题进度复用契约）+ 断线降级轮询 status。
11. **验证口径**：Tika 解析是纯 Java，解析测试本机跑真文件；S3 / embedding 一律 Fake；`@Tag("docker")` IT 只验迁移与真 PG 向量列。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 解析输出纯文本（🅖 形态） | 引用跳转、标题加权、`analyzer_version` 只重分块三个需求全部落空；结构丢失后不可逆 |
| 向量存共享 `vector_store` + metadata JSON 过滤（🅖 形态） | metadata 过滤脆弱（上游为此背了 `kb_id_long` 兼容 hack）；heading path / 偏移 / content_hash 无处安放 |
| chunk 输出裸 `string[]`（🅢 形态） | 无偏移无标题路径，P1a-08 引用跳转不成立 |
| shadow-generation 双代向量（🅖 临时 job promote 模式） | 复杂度 v1 不值：annona 分块是可从 S3+原文再生的派生数据；重评触发 = 单 doc 向量化时长长到"失败重跑"成为真实痛点 |
| Embedding 并入 `ModelProvider.chat` 同接口 | chat 与 embedding 的配置、配额、失败语义不同；SPI javadoc 已声明其为独立扩展点 |
| 启动 fail-fast 校验 embedding Key | `none` 模式与本地无 Key 环境也要能起；错误后移到使用点更诚实 |
| 按 token 计量分块 | 引入分词器依赖；800 字符 ≪ embedding 输入上限（🅢 定标逻辑），字符口径留足余量 |
| 外置解析/向量化服务（tika-server、独立向量 worker） | 违反单机自部署原则；Tika 库内嵌 + 专用小线程池（2 线程、2m 超时）足够 |

## 后果与约束

- `kb_doc` / `kb_doc_chunk` 有数据后即冻结；结构变更一律 V5+。`embedding vector(1024)` 维度冻结于 DDL，换模型或换维度 = 新迁移重建索引。
- `modules/knowledge` 禁止 import infrastructure；S3 / Tika / Redisson 只经端口触达。
- LLM（embedding）与 S3 调用一律在事务外；事务只包本地状态写与分块行写（overview §4 铁律）。
- SSE 进度信封五字段是跨阶段契约；P1b-02 复用时不得私改字段语义，不够就加字段。
- 分块阈值（800/50/0.6）写在 `ChunkOptions` 并注释来源（🅢 实测值）；调优推迟到 P1a-09 有实测数字之后。
- hash 幂等键为 `(user_id, file_hash)`：同用户重复上传秒级返回 `duplicate=true` 零消耗；跨用户不互窥。

## 何时重新评估

- 换 embedding 模型或维度 ≠ 1024（触发多版本并存表与 V5 迁移讨论）。
- P1a-09 实测显示 800 字符块粒度过粗/过细（以 Recall@K / MRR 数字为准，不凭感觉调）。
- 真实使用出现大文件解析超时频发（调 parse 池参数或引入流式解析）。
- P1b-10 BYOK 落地：EmbeddingProvider 需要按用户 Key 路由时，扩展 provider 解析层而非改接口。
- **分块边界落在 ASCII 标识符中间**（已确认缺陷，未修）。2026-09-28 批 1 手工验收实测：真 PDF 入库后有一个块以
  `lication.properties` 开头（`application` 被从第 4 个字符切开）。根因不是 800/50/0.6 三个阈值，而是
  `Chunker.windowRanges` 的重叠推进：`advance = (end-start) - overlap` 把每一片（除首片）的起点定在
  “上一片边界前 50 字”这个任意位置上；找不到句末断点时的硬切同理。中文不在影响面内（靠 jieba 分词），
  伤的是引用面板可读性与标识符检索。
  **首次修复尝试为何回退**（2026-09-28，加 `retreatToAtomicStart` / `advanceToAtomicEnd` 两行边界对齐）：
  撞坏 3 条现有行为规格——golden `sentence-window` 变成以 `。` 开头且丢上下文、`overlapAdvancesWindow`
  由 3 片变 4 片（重叠语义被改）、不变量测试自身仍红（标识符跨 `floor` 时只能退到 `floor`，还是切在串里）。
  结论：这不是两行能收的改动。**重开时必须一起做**：① 先定“起点落在标识符内 → 前推还是后移”的语义（前推会
  丢重叠上下文，后移会把标识符同时留在两片）；② 重写 `sentence-window` golden（它现在编码的就是错误行为）；
  ③ `Chunker.VERSION` 升 `char-v2`（算法行为变更必须换版本号）→ 存量文档全部需要“重建”；④ 覆盖率与
  `analyzer_version` 不匹配时的可见提醒（否则用户不知道为什么旧文档分块看起来奇怪）。批 3 的引用面板会把这个
  缺陷从“数据不好看”放大成“用户看得见”，所以它必须在 P1a-08 验收**之前**落地，但不阻塞开工。
