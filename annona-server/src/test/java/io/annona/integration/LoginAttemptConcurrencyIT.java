package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.identity.entity.LoginAttemptEntity;
import io.annona.modules.identity.repository.LoginAttemptRepository;
import io.annona.modules.identity.service.LoginAttemptStore;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 登录失败计数的并发安全（真实 PG）。此前的「findById→累加→save」非原子读-改-写会丢失
 * 并发更新，并发暴破可拖慢甚至绕过 10 次锁定阈值；现改为 insert-ignore 幂等建行 + 行锁
 * 串行化（见 identity ADR「后续修订」加固批）。本机不跑；CI docker-it 执行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("LoginAttemptStore：并发失败计数不丢失（行锁串行化）")
class LoginAttemptConcurrencyIT {

    @Autowired
    private LoginAttemptStore attemptStore;
    @Autowired
    private LoginAttemptRepository attemptRepository;

    @Test
    @DisplayName("20 个并发失败全部计入：fail_count == 20 且已上锁，无丢失更新")
    void concurrentFailuresAreAllCounted() {
        String key = "concurrency-" + UUID.randomUUID() + "@example.test|10.7.7.7";
        IntStream.range(0, 20).parallel().forEach(i -> attemptStore.recordFailure(key));

        LoginAttemptEntity row = attemptRepository.findById(key).orElseThrow();
        // 丢失更新会让计数小于 20；行锁串行化后此断言是确定性的
        assertThat(row.getFailCount()).isEqualTo(20);
        assertThat(row.getLockedUntil()).isNotNull();
    }
}
