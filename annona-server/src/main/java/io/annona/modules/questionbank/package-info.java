/**
 * questionbank 知识库出题（P1b-02/03；skill-questionbank-adr）。
 *
 * <p>职责：从方向绑定的知识库分块异步生成题目（主问题 + 参考答案 + 关键点 + 评分标准 +
 * 追问），任务状态机 QUEUED→PROCESSING→COMPLETED/FAILED（Redis Stream 异步 + taskId
 * fencing + 恢复调度）；题库维护与容量校验（P1b-03）。
 *
 * <p>允许依赖：{@code io.annona.common}（TaskStreamPort/StructuredOutputInvoker/ErrorCode）、
 * {@code io.annona.spi}（ModelProvider）、{@code io.annona.shared.*} 只读（DirectionQueryService）、
 * 跨模块只读注入 retrieval/knowledge 的 QueryService（qa 先例）。LLM/检索调用一律事务外，
 * 跑 aiIoExecutor；写库最小短事务。禁止依赖 infrastructure 与其他模块内部包。
 */
package io.annona.modules.questionbank;
