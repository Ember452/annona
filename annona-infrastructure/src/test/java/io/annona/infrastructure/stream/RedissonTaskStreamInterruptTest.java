package io.annona.infrastructure.stream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 关停判据单测（纯逻辑，不接 Redis）。
 *
 * <p>要守的行为是：消费线程在 {@code @PreDestroy} 中断时**安静退出**，而不是把中断当成
 * Redis 故障记 WARN 再睡一轮。Redisson 实际抛的是被包了一层的
 * {@code RedisException(InterruptedException)}（CI 关停日志实证），所以判据必须看异常链，
 * 不能只看顶层类型——这条测试就是钉住这件事：哪天包装层级变了，判据会先红在这里，
 * 而不是退回"每次关停都刷一条错误日志"。
 */
@DisplayName("RedissonTaskStream：中断与真实故障的区分")
class RedissonTaskStreamInterruptTest {

    @Test
    @DisplayName("Redisson 那种包了一层的中断也算中断")
    void wrappedInterruptCounts() {
        RuntimeException wrapped = new RuntimeException("readGroup failed",
            new IllegalStateException("cmd interrupted", new InterruptedException()));

        assertThat(RedissonTaskStream.causedByInterrupt(wrapped)).isTrue();
    }

    @Test
    @DisplayName("直接抛出的 InterruptedException 也算")
    void directInterruptCounts() {
        assertThat(RedissonTaskStream.causedByInterrupt(
            new RuntimeException("boom", new InterruptedException()))).isTrue();
    }

    @Test
    @DisplayName("真实 Redis 故障不算中断：必须继续走退避重试，不能悄悄停掉消费")
    void realFailureIsNotAnInterrupt() {
        assertThat(RedissonTaskStream.causedByInterrupt(
            new RuntimeException("connection refused"))).isFalse();
        assertThat(RedissonTaskStream.causedByInterrupt(
            new RuntimeException(new IllegalStateException("READONLY")))).isFalse();
    }

    @Test
    @DisplayName("自引用的异常链不会把判据拖进死循环")
    void selfReferencingCauseTerminates() {
        RuntimeException loop = new RuntimeException() {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(RedissonTaskStream.causedByInterrupt(loop)).isFalse();
    }
}
