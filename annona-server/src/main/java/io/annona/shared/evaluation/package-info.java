/**
 * 面试评估结果跨模块读模型：shared/signal 门面经 {@link EvaluationSignalPort} 只读消费
 * evaluation 的 DONE 报告（含四留痕），作为掌握度主输入。实现方在
 * {@code io.annona.modules.evaluation}；返回类型复用 {@code io.annona.spi.dto.SessionOutcome}
 * （shared 允许依赖 spi）。
 */
package io.annona.shared.evaluation;
