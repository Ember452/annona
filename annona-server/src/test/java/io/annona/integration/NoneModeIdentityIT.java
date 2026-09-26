package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.identity.provider.NoneIdentityProvider;
import io.annona.modules.identity.repository.AppUserRepository;
import io.annona.modules.identity.repository.UserProfileRepository;
import io.annona.modules.identity.service.AuthUserRegistrar;
import io.annona.spi.dto.Principal;
import io.annona.spi.identity.IdentityProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code none}（单机免登录）模式的启动 bootstrap（真实 PG）：固定 UUID 用户与其资料在启动后即就绪，
 * 免凭据即可认证。本机不跑；CI 的 docker-it job（services: pgvector + redis）执行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "annona.identity.mode=none")
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("identity：none 模式 bootstrap（真实 PG）")
class NoneModeIdentityIT {

    private static final UUID LOCAL_USER_ID = NoneIdentityProvider.LOCAL_USER_ID;

    @Autowired
    private IdentityProvider identityProvider;
    @Autowired
    private AuthUserRegistrar registrar;
    @Autowired
    private AppUserRepository userRepository;
    @Autowired
    private UserProfileRepository profileRepository;

    @Test
    @DisplayName("启动后固定 UUID 用户与其 user_profile 存在，且免凭据认证返回该主体")
    void bootstrapUserReady() {
        assertThat(identityProvider.mode()).isEqualTo("none");
        assertThat(userRepository.findById(LOCAL_USER_ID)).isPresent();
        assertThat(profileRepository.findById(LOCAL_USER_ID)).isPresent();

        Optional<Principal> principal = identityProvider.authenticate(null);
        assertThat(principal).isPresent();
        assertThat(principal.get().id()).isEqualTo(LOCAL_USER_ID.toString());
    }

    @Test
    @DisplayName("bootstrap 幂等：重复调用不报错、不产生第二行")
    void bootstrapIsIdempotent() {
        long before = userRepository.count();

        registrar.ensureLocalBootstrap(LOCAL_USER_ID, NoneIdentityProvider.BOOTSTRAP_EMAIL);
        registrar.ensureLocalBootstrap(LOCAL_USER_ID, NoneIdentityProvider.BOOTSTRAP_EMAIL);

        assertThat(userRepository.count()).isEqualTo(before);
    }
}