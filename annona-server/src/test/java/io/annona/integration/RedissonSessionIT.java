package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.common.session.SessionStore;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * SessionStore 的 Redisson 实现端到端（真实 Redis）：签发→读取→续期→删除。
 * 本机不跑；CI docker-it job（services 含 redis）执行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("SessionStore：Redis 会话签发/读取/续期/删除")
class RedissonSessionIT {

    @Autowired
    private SessionStore sessionStore;

    @Test
    @DisplayName("create 后能读回 userId，delete 后为空")
    void createReadDelete() {
        String token = UUID.randomUUID().toString();
        sessionStore.create(token, "user-42", Duration.ofMinutes(5));
        assertThat(sessionStore.readUserId(token)).contains("user-42");

        sessionStore.touch(token, Duration.ofMinutes(5));
        assertThat(sessionStore.readUserId(token)).contains("user-42");

        sessionStore.delete(token);
        assertThat(sessionStore.readUserId(token)).isEmpty();
    }

    @Test
    @DisplayName("空/未知令牌返回 empty，不抛异常")
    void unknownTokenIsEmpty() {
        assertThat(sessionStore.readUserId(null)).isEmpty();
        assertThat(sessionStore.readUserId("nope-" + UUID.randomUUID())).isEmpty();
    }
}
