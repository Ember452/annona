package io.annona.modules.identity.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.annona.modules.identity.dto.AuthUserResponse;
import io.annona.modules.identity.entity.AppUserEntity;
import io.annona.modules.identity.repository.AppUserRepository;
import io.annona.modules.identity.service.AuthUserRegistrar;
import io.annona.modules.identity.service.IdentityProperties;
import io.annona.spi.dto.Principal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** {@link PlatformIdentityProvider}：受信反代头 → 本地账号（含 JIT 建号，纯逻辑本机跑）。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformIdentityProvider：受信反代头 → 本地账号")
class PlatformIdentityProviderTest {

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private AuthUserRegistrar registrar;

    private PlatformIdentityProvider provider;

    @BeforeEach
    void setUp() {
        provider = new PlatformIdentityProvider(userRepository, registrar, new IdentityProperties());
    }

    private static AppUserEntity activeUser(String email) {
        AppUserEntity user = new AppUserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setStatus("ACTIVE");
        user.setRole("USER");
        return user;
    }

    @Test
    @DisplayName("头缺失或非法邮箱：empty，且绝不据此建号")
    void rejectsInvalidEmailWithoutProvisioning() {
        assertThat(provider.authenticate(null)).isEmpty();
        assertThat(provider.authenticate("")).isEmpty();
        assertThat(provider.authenticate("not-an-email")).isEmpty();
        assertThat(provider.authenticate("a".repeat(300) + "@example.test")).isEmpty();
        verifyNoInteractions(userRepository, registrar);
    }

    @Test
    @DisplayName("邮箱已存在：直接返回该主体，不触发 JIT 建号")
    void returnsExistingUser() {
        AppUserEntity user = activeUser("known@example.test");
        when(userRepository.findActiveByEmail("known@example.test")).thenReturn(Optional.of(user));

        Optional<Principal> principal = provider.authenticate("Known@Example.Test");

        assertThat(principal).isPresent();
        assertThat(principal.get().id()).isEqualTo(user.getId().toString());
        assertThat(principal.get().displayName()).isEqualTo("known@example.test");
        assertThat(principal.get().roles()).containsExactly("USER");
        verify(registrar, never()).provisionExternal(anyString());
    }

    @Test
    @DisplayName("未知邮箱：调用 provisionExternal JIT 建号并返回新主体")
    void provisionsUnknownUser() {
        when(userRepository.findActiveByEmail("new@example.test")).thenReturn(Optional.empty());
        when(registrar.provisionExternal("new@example.test"))
            .thenReturn(new AuthUserResponse("22222222-2222-2222-2222-222222222222", "new@example.test", "USER"));

        Optional<Principal> principal = provider.authenticate("new@example.test");

        assertThat(principal).isPresent();
        assertThat(principal.get().id()).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(principal.get().roles()).containsExactly("USER");
    }

    @Test
    @DisplayName("并发首访（唯一索引冲突）：回读既有行而非报错")
    void recoversFromConcurrentFirstVisit() {
        AppUserEntity winner = activeUser("race@example.test");
        when(userRepository.findActiveByEmail("race@example.test"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(winner));
        when(registrar.provisionExternal("race@example.test"))
            .thenThrow(new DataIntegrityViolationException("app_user_email_key 冲突"));

        Optional<Principal> principal = provider.authenticate("race@example.test");

        assertThat(principal).isPresent();
        assertThat(principal.get().id()).isEqualTo(winner.getId().toString());
    }

    @Test
    @DisplayName("mode() 为 platform")
    void modeIsPlatform() {
        assertThat(provider.mode()).isEqualTo("platform");
    }
}