/**
 * 流式问答模块（P1a-08，qa-streaming-adr）：SSE 四事件契约（token/sources/done/error）、
 * 会话与消息持久化（V6）、结构化引用（citations JSONB）与空命中诊断透传。
 *
 * <p>允许依赖：retrieval（只读经 RetrievalQueryService，全仓首个跨模块只读先例）、
 * knowledge（只读经 KnowledgeDocQueryService 回查分块正文）、spi / common。
 * 禁止：import 其他 modules 的内部包；LLM 流式调用进事务（落库只有占位与回填两个短事务）。
 */
package io.annona.modules.qa;
