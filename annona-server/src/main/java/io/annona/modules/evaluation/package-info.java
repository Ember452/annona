/**
 * evaluation 面试评估链（P1b-06/07；evaluation-pipeline-adr）。
 *
 * <p>职责：交卷后异步逐题评估（分批 + 二次汇总走 StructuredOutputInvoker）→ 难度加权总分 →
 * 可解释报告（interview_evaluation 明细 + interview_report 汇总/状态）。触发经 interview
 * 发布的 {@code InterviewFinalizedEvent}（写走领域事件，§4）；执行走 Redis Stream + 恢复调度
 * （TaskStreamPort 形态，同 questionbank）。
 *
 * <p>允许依赖：{@code io.annona.common}（TaskStreamPort/UsageContext/异常）、{@code io.annona.spi}
 * （ModelProvider 经 shared StructuredOutputInvoker）、{@code io.annona.shared.*} 读端口
 * （interview 作答、question 评分口径、direction）、{@code io.annona.shared.ai} 与
 * {@code io.annona.shared.progress}。禁止 import {@code io.annona.modules.*} 其它业务模块
 * （读走 shared、触发走事件）；LLM 调用一律事务外，DB 写最小短事务。
 */
package io.annona.modules.evaluation;
