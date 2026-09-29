/**
 * 异步任务进度的冻结信封与 SSE 广播枢纽（多模块共用）。
 *
 * <p>本包存在的原因：知识库入库（P1a-05）与题库出题（P1b-02）都要向前端推进度，
 * 二者是不同业务模块，进度形状必须单一来源，否则两处信封会各自漂移。
 * {@link io.annona.shared.progress.ProgressEvent} 是冻结契约（改字段要同步所有消费方
 * 与 skill-questionbank-adr），{@link io.annona.shared.progress.SseProgressHub}
 * 是仓库首个 SSE 先例（消费者线程 publish、controller 订阅、断线降级走轮询）。
 *
 * <p>允许依赖：JDK、Spring（Web SSE）、SLF4J、Jackson。
 * 禁止：依赖 {@code io.annona.modules.*} 或 {@code io.annona.infrastructure.*}。
 *
 * <p>当前内容：{@code ProgressEvent} / {@code SseProgressHub}（P1a-05 起，P1b-02 复用）。
 */
package io.annona.shared.progress;
