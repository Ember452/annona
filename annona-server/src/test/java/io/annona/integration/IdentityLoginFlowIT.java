package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.identity.dto.AuthUserResponse;
import io.annona.modules.identity.service.AuthUserRegistrar;
import io.annona.modules.identity.service.ChangePasswordService;
import io.annona.modules.identity.service.LoginOutcome;
import io.annona.modules.identity.service.LoginService;
import io.annona.modules.identity.service.UserQueryService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 身份闭环端到端（真实 PG + Redis）：注册→登录→改密→旧口令失效、错密拒绝、连续失败锁定。
 * 本机不跑；CI 的 docker-it job（services: pgvector + redis）执行。见 dockerless ADR。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("identity：注册/登录/改密/锁定（真实 PG+Redis）")
class IdentityLoginFlowIT {

    @Autowired
    private AuthUserRegistrar registrar;
    @Autowired
    private LoginService loginService;
    @Autowired
    private ChangePasswordService changePasswordService;
    @Autowired
    private UserQueryService userQueryService;

    private static String uniqueEmail() {
        return "p1a01-" + UUID.randomUUID() + "@example.test";
    }

    @Test
    @DisplayName("注册→登录签发会话→改密后旧口令失效、新口令可登录")
    void registerLoginChangePassword() {
        String email = uniqueEmail();
        AuthUserResponse user = registrar.register(email, "OldPass123");
        assertThat(user.email()).isEqualTo(email.toLowerCase());

        LoginOutcome login = loginService.login(email, "OldPass123", "10.0.0.1", null, "junit");
        assertThat(login.token()).isNotBlank();
        assertThat(userQueryService.loadByUserId(login.userId()).id()).isEqualTo(user.id());

        changePasswordService.change(user.id(), "OldPass123", "NewPass456");

        assertThatThrownBy(() -> loginService.login(email, "OldPass123", "10.0.0.2", null, "junit"))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCode.INVALID_CREDENTIALS.getCode());

        assertThat(loginService.login(email, "NewPass456", "10.0.0.3", null, "junit").token())
            .isNotBlank();
    }

    @Test
    @DisplayName("连续 10 次错误口令后账号被锁定")
    void locksAfterTenFailures() {
        String email = uniqueEmail();
        registrar.register(email, "GoodPass123");
        String ip = "10.9.9.9";
        for (int i = 0; i < 10; i++) {
            int attempt = i;
            assertThatThrownBy(() -> loginService.login(email, "bad" + attempt, ip, null, "junit"))
                .isInstanceOf(BusinessException.class);
        }
        // 第 11 次即便口令正确也应被锁定（锁定期内）
        assertThatThrownBy(() -> loginService.login(email, "GoodPass123", ip, null, "junit"))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCode.ACCOUNT_LOCKED.getCode());
    }
}
