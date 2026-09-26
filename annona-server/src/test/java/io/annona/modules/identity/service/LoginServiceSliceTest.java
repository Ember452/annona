package io.annona.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.session.SessionStore;
import io.annona.modules.identity.entity.AppUserEntity;
import io.annona.modules.identity.repository.AppUserRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * LoginService 的 Mockito 切片：坐实 identity ADR §后果 §2「user_session 审计投影写失败
 * 只 warn、不影响登录」——P1a-01 验收项之一，此前只有 try/catch 代码、无测试。
 * 口令哈希用真 scrypt（与生产同参数），其余依赖全 mock。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("LoginService：审计投影写失败不影响登录（ADR §后果 §2）")
class LoginServiceSliceTest {

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private LoginAttemptStore attemptStore;
    @Mock
    private SessionStore sessionStore;
    @Mock
    private SessionProjectionWriter projectionWriter;

    private LoginService loginService;

    @BeforeEach
    void setUp() {
        PasswordHasher hasher = new PasswordHasher();
        SessionProperties props = new SessionProperties();
        loginService = new LoginService(userRepository, hasher, attemptStore, sessionStore,
            projectionWriter, props);
    }

    @Test
    @DisplayName("投影 record 抛 RuntimeException 时登录仍签发令牌并重置失败计数")
    void projectionFailureDoesNotBreakLogin() {
        AppUserEntity user = new AppUserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("slice@example.test");
        user.setPasswordHash(new PasswordHasher().hash("SlicePass123"));
        user.setPasswordAlgo(PasswordHasher.ALGO_TAG);
        user.setStatus("ACTIVE");
        user.setRole("USER");
        when(userRepository.findActiveByEmail("slice@example.test")).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("user_session 投影写失败模拟"))
            .when(projectionWriter).record(any(), anyString(), any(), any());

        LoginOutcome outcome = loginService.login("slice@example.test", "SlicePass123", "10.0.0.1", null, "junit");

        assertThat(outcome.token()).isNotBlank();
        assertThat(outcome.userId()).isEqualTo(user.getId().toString());
        verify(sessionStore).create(eq(outcome.token()), eq(user.getId().toString()), any(Duration.class));
        verify(attemptStore).reset("slice@example.test|10.0.0.1");
    }
}
