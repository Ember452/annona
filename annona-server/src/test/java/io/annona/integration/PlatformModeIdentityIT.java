package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.identity.repository.AppUserRepository;
import io.annona.modules.identity.repository.UserProfileRepository;
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
 * {@code platform} 模式的 JIT 建号（真实 PG）：受信反代头首次出现时建号，再次出现复用同一账号。
 * 本机不跑；CI 的 docker-it job（services: pgvector + redis）执行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "annona.identity.mode=platform")
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("identity：platform 模式 JIT 建号（真实 PG）")
class PlatformModeIdentityIT {

    @Autowired
    private IdentityProvider identityProvider;
    @Autowired
    private AppUserRepository userRepository;
    @Autowired
    private UserProfileRepository profileRepository;

    @Test
    @DisplayName("首次受信头 JIT 建号，再次认证返回同一 userId（幂等），资料同步创建")
    void jitProvisioningIsIdempotent() {
        assertThat(identityProvider.mode()).isEqualTo("platform");
        String email = "p1a02-" + UUID.randomUUID() + "@example.test";

        Optional<Principal> first = identityProvider.authenticate(email);
        assertThat(first).isPresent();
        assertThat(profileRepository.findById(UUID.fromString(first.get().id()))).isPresent();

        Optional<Principal> second = identityProvider.authenticate(email);
        assertThat(second).isPresent();
        assertThat(second.get().id()).isEqualTo(first.get().id());
        assertThat(userRepository.findActiveByEmail(email)).isPresent();
    }

    @Test
    @DisplayName("非法头（非邮箱）不建号")
    void invalidHeaderDoesNotProvision() {
        assertThat(identityProvider.authenticate("not-an-email")).isEmpty();
        assertThat(userRepository.findActiveByEmail("not-an-email")).isEmpty();
    }
}