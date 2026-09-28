# ADR: 流式问答——chat 模型层从零落成，流式端口进 common，SSE 四事件契约

- 日期 / 状态：2026-09-28 / Accepted
- 相关文档：[model-api-key-adr](./2026-09-25-model-api-key-adr.md) · [knowledge-ingestion-adr](./2026-09-27-knowledge-ingestion-adr.md) · [retrieval-hybrid-adr](./2026-09-28-retrieval-hybrid-adr.md) · [dockerless-local-dev-adr](./2026-09-25-dockerless-local-dev-adr.md) · 开发计划 P1a-08

## 背景

`ModelProvider` 只承诺同步非流式语义，唯一实现是 `spi/fake/FakeModelProvider`；`application.yaml` 只有 `annona.model.embedding.*`。所以 P1a-08 不是"给问答加流式"，而是**从零建 chat 模型层**（同步 + 流式一起落，同步侧 evaluation 在 P1b 直接需要）。

端口归属按 AGENTS §4 的两条正交判据走：① **有没有外部实现方需求**——BYOK（P1b-10）交给用户的是 Base URL 和 Key，不是 Java 类；P1a/P1b 的实现方只有自家 OpenAI 兼容实现与测试 Fake，没有第三方写实现的真实预期 → 内部解耦端口进 `annona-common`。② 零 SDK ——JDK HttpClient 足够。与 `ModelProvider` 已在 spi 的不一致：承认它是 P1a-02"模型网关扩展点"的整体历史决定，不追溯迁移；但流式协议比同步 chat **易变得多**（reasoning 增量、tool-call 分片、多模态分块任一都是破坏性变更），`annona-spi` 是唯一发 Central 的 artifact 且从未打过 tag——现在把 `onDelta/onComplete/onError` 发布出去，等于冻结一个注定要动的协议。

## 决策

1. **`StreamingChatProvider` + `ChatStreamListener`（`onDelta` / `onComplete` / `onError`）进 `annona-common/model`**。回调式而非返回 `Flux`：common 不引 Reactor，回调与 `SseEmitter` 一一对应。javadoc 写明前置条件、三类失败语义（连接失败 / 中断 / 上游非 2xx）与"实现不得在回调线程做阻塞 IO"。
2. **`OpenAiCompatibleChatProvider`（infrastructure）一个类同时实现 `ModelProvider`（同步）与 `StreamingChatProvider`（流式）**，共用 HttpClient、超时、错误映射、base-url 处理；流式走 `HttpResponse.BodyHandlers.ofLines()` 解析 `data:` 帧与 `[DONE]`。`name()` 返回模型 id（与 embedding 同口径，供用量归属）。
3. **SSE 事件契约**：`token {delta}` / `sources {citations[], reason?}` / `done {messageId}` / `error {code, message}`；载荷一律 JSON 信封——Jackson 自动转义换行，🅖 的手工 `\n` 转义在结构化信封下不需要。前端 `fetch` + `ReadableStream` 手解帧：`EventSource` 只支持 GET 且不能带 body，问答是 POST；与知识库进度 SSE（`EventSource` + knowledge-ingestion-adr 决策 10 的五字段信封）并存，两种传输各自配解析器。
4. **V6：`qa_session` / `qa_message` 两表**。命名沿用全仓 `*_session`，但表 `COMMENT` 显式声明与 `user_session`（登录态审计投影）、`study_session`（一次专注 + quality 分级）、`interview_session`（一场评估 + finalize 幂等）**不同构、不共享不变量**——命名统一不制造语义统一的错觉。`citations` 选 `JSONB` + `@JdbcTypeCode(SqlTypes.JSON)`（Boot 4.1.1 → Hibernate 7），`QaFlowIT` 在真 PG 上证伪往返；受阻降级 `TEXT` + 显式转换器。
5. **`FakeStreamingChatProvider` 落 `annona-infrastructure`，不进 `spi/fake`**：spi 的零依赖契约（`ModelProvider` javadoc、ArchUnit 规则 5）不允许 spi→common，而 infra→common 依赖已存在；与 `FakeModelProvider`（spi 自带接口的自实现）不同构，勿并列类比。
6. **渲染净化**：`react-markdown@9` + `remark-gfm`，**不装 `rehype-raw`**——原生 HTML 由构造不渲染，XSS 面直接关闭，替代 🅢 的 sanitize 白名单方案；同源 vitest 断言 `<script>`、`<img onerror>` 按纯文本渲染。
7. **跨模块只读先例**：`qa → retrieval` 只读消费 `RetrievalQueryService`（全仓首个真正的跨模块消费，走 AGENTS 规定的 `XxxQueryService` 路径，单向不成环，ArchUnit 环规则仍绿）；`qa → knowledge` 只读批量回查分块正文/标题路径/偏移（新增 QueryService 读方法），正文不进 SPI DTO。
8. **会话生命周期借 🅖**：短事务①先落 USER 行 + ASSISTANT 空占位（`completed=false`），流结束短事务②一次性回填（非增量追加）；客户端断线保留已生成部分；追问组装上下文只取 `completed=true` 的消息；首问标题取问题前 20 字（🅢 的回退口径，一期不调 LLM 起标题）。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 🅢 裸文本流 + `X-Conversation-Id` 响应头 | 无事件类型，引用与错误无法结构化下发；"错误伪装成正常文本"吞掉 2502/1104 的可解释性，违背 AGENTS §1 |
| 🅖 `Flux<ServerSentEvent>` | 引 WebFlux/Reactor 进 MVC 栈；common 端口因此选回调式 |
| 流式端口进 `annona-spi` | 见背景：把最易变的协议冻结进唯一发 Central 的 artifact |
| `FakeStreamingChatProvider` 进 `spi/fake` | 需要 spi→common 依赖，破坏 spi 零依赖契约 |
| `rehype-raw` + sanitize schema（🅖/🅢 方案） | 白名单是持续维护税；不渲染原生 HTML 从构造上消除 XSS 面 |
| `qa_conversation` 命名（🅢 prisma 形） | 全仓 `session` 一词一义；引入 `conversation` 即第二个词 |
| 会话全量功能（置顶 / 归档 / 多库关联） | P1a-08 验收不需要；🅖 的 `status`/`isPinned` 等真实需要出现再加 |
| 只做流式不补同步 chat | `ModelProvider` 已承诺同步语义且 P1b evaluation 直接需要；一个实现类顺路补齐，零新增配置面 |
| StringTemplate/ST4 渲染 prompt | 占位符只有 `{context}`/`{question}` 两个字面替换；为两个替换引模板引擎违反 Simplicity First（`.st` 后缀只沿用 🅖 的文件组织习惯，加载即 classpath 文本 + 字面替换） |
| 探测窗口（🅖 120 字符拒答短路） | 依赖上游拒答模板措辞，脆弱且本期非目标（AGENTS 明确不做清单） |

## 后果与约束

- LLM 流式调用不得进事务（AGENTS §0 铁律）；落库只允许短事务①（占位）与短事务②（回填）。
- 错误码按需续号不预留：AI 段 `AI_STREAM_FAILED(1104)`、`AI_STREAM_INTERRUPTED(1105)`；qa 段 `QA_SESSION_NOT_FOUND(2500)`、`QA_MESSAGE_NOT_FOUND(2501)`、`QA_MODEL_NOT_CONFIGURED(2502)`。
- `message_order` 以 max+1 生成，同会话并发提问由 `unique(session_id, message_order)` 兜底报错可重试——P1a 单用户可接受，不做会话级锁。
- `ChatProperties` 默认值单一出处纪律照批 2：只写 `application.yaml` 的 `${ENV:default}`，record 不留字段初值（`PropertiesDefaultSourceTest` 门禁）；环境变量与属性路径不同形（`ANNONA_CHAT_BASE_URL` → `annona.model.chat.base-url`），显式 `${}` 映射，不指望 relaxed binding。
- 空命中时检索的 `diagnostics.reason`（`NO_READY_DOC` / `MODEL_MISMATCH` / `NO_MATCH`）作为 `sources` 事件的一部分透传并渲染成一行"凭什么没找到"——AGENTS §1 可解释性的最小兑现。
- P1a-08 只消费检索的 `score` 与 `chunkId`（批 2 定死口径），P1a-09b 重评检索方案对本批代码零返工。

## 何时重新评估

- 出现第二个真实外部实现方，或 P1b-10 BYOK 要求用户自定义 adapter → 重评把两个 chat 端口一起升 spi（保持同步/流式同处）。
- reasoning 增量 / tool-call 分片 / 多模态分块任一进需求 → 在 `token` 事件上**加字段**而非换事件语义；若需新事件类型，走本 ADR 修订。
- JSONB 往返在 Hibernate 7 上受阻 → 降级 `TEXT` + 显式转换器（`QaFlowIT` 是证伪点）。
- 同会话并发提问成为真实场景 → 会话级锁或 ask 幂等键。
