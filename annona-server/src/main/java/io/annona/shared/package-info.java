/**
 * annona-server 的跨模块共享内核包。<b>不放业务规则</b>，只放契约与读模型。
 *
 * <p>子包（各自有 package-info 说明；P1a 起逐个子包落地）：
 * <ul>
 *   <li>{@code domain/}——领域事件定义（{@code StudySessionClosed / InterviewEvaluated} …）</li>
 *   <li>{@code direction/}——方向主数据只读访问（被 6 个模块消费，独立于 identity/study）</li>
 *   <li>{@code signal/}——{@code LearningSignalReader} 的门面与信号快照模型</li>
 *   <li>{@code idempotent/}——幂等键生成与消费模板（交卷、回写、异步任务）</li>
 * </ul>
 *
 * <p>允许依赖：{@code io.annona.common}、{@code io.annona.spi}、JDK、Spring（装配）。
 * 禁止：依赖 {@code io.annona.modules.*}（shared 不能反向依赖业务）。
 */
package io.annona.shared;
