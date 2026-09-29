package io.annona.common.usage;

import java.util.Optional;
import java.util.UUID;

/**
 * 用量归属上下文（llmprovider-metering-adr §记账）：模型出口在 common/spi 层拿不到
 * "这是谁、哪个场景、哪次会话"的调用，由业务入口 bind、计量装饰器读取——避免把 userId
 * 塞进 {@code ModelOptions}（spi 是对外契约，不为内部记账扩字段）。
 *
 * <p>线程约束（易错点，注释钉死）：ThreadLocal 不跨线程传播，异步/流式链路必须在
 * <b>执行线程内</b> bind（线程池复用线程，try-with-resources 的 close 负责清空——
 * 漏 close 会把上一个请求的场景身份泄漏给下一个任务）。
 */
public final class UsageContext {

    /**
     * 当前归属。
     *
     * @param userId           计费主体
     * @param scene            INTERVIEW / QUESTION_GEN / QA / EVALUATION
     * @param sessionId        场景宿主（可空）
     * @param evaluatorVersion 评估器版本（非评估调用为空）
     */
    public record Attribution(String userId, String scene, UUID sessionId, String evaluatorVersion) {
    }

    private static final ThreadLocal<Attribution> CURRENT = new ThreadLocal<>();

    private UsageContext() {
    }

    /** 绑定并返回作用域句柄；必须 close（try-with-resources）。 */
    public static Scope bind(String userId, String scene, UUID sessionId, String evaluatorVersion) {
        Attribution previous = CURRENT.get();
        CURRENT.set(new Attribution(userId, scene, sessionId, evaluatorVersion));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static Optional<Attribution> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** close 恢复外层绑定（支持嵌套：评估中再调 chat 不串场景）。 */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
