package io.annona.common.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 线程内绑定的三条铁律：close 清空、嵌套恢复、跨线程不传播。 */
class UsageContextTest {

    private static final UUID SESSION = UUID.randomUUID();

    @Test
    @DisplayName("bind 后 current 可见；close 后恢复 empty（线程池复用不串号）")
    void bindAndClose() {
        assertThat(UsageContext.current()).isEmpty();
        var scope = UsageContext.bind("u1", "QA", SESSION, null);
        assertThat(UsageContext.current()).get()
            .satisfies(a -> assertThat(a.scene()).isEqualTo("QA"));
        scope.close();
        assertThat(UsageContext.current()).isEmpty();
    }

    @Test
    @DisplayName("嵌套 bind：内层 close 恢复外层归属（评估中再调 chat 不丢外层场景）")
    void nestedBindRestoresOuter() {
        try (UsageContext.Scope outer = UsageContext.bind("u1", "EVALUATION", SESSION, "v2")) {
            try (UsageContext.Scope inner = UsageContext.bind("u2", "QUESTION_GEN", null, null)) {
                assertThat(UsageContext.current()).get()
                    .satisfies(a -> assertThat(a.userId()).isEqualTo("u2"));
            }
            assertThat(UsageContext.current()).get()
                .satisfies(a -> assertThat(a.evaluatorVersion()).isEqualTo("v2"));
        }
        assertThat(UsageContext.current()).isEmpty();
    }

    @Test
    @DisplayName("新线程读不到主线程绑定（文档化的异步 bind 纪律的依据）")
    void doesNotLeakAcrossThreads() throws InterruptedException {
        var seen = new AtomicReference<Optional<UsageContext.Attribution>>();
        try (UsageContext.Scope scope = UsageContext.bind("u1", "QA", SESSION, null)) {
            Thread.ofVirtual().start(
                (Runnable) () -> seen.set(UsageContext.current())).join();
        }
        assertThat(seen.get()).isEmpty();
    }
}
