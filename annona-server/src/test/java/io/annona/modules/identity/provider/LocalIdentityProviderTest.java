package io.annona.modules.identity.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.annona.modules.identity.service.SessionService;
import io.annona.spi.dto.Principal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link LocalIdentityProvider}：会话令牌 → 主体（纯逻辑，本机跑）。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LocalIdentityProvider：会话令牌 → 主体")
class LocalIdentityProviderTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private SessionService sessionService;

    private LocalIdentityProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LocalIdentityProvider(sessionService);
    }

    @Test
    @DisplayName("有效令牌：返回 userId 主体并触发滑动续期")
    void resolvesValidToken() {
        when(sessionService.resolveAndSlide("tok")).thenReturn(Optional.of(USER_ID));

        Optional<Principal> principal = provider.authenticate("tok");

        assertThat(principal).isPresent();
        assertThat(principal.get().id()).isEqualTo(USER_ID);
        assertThat(principal.get().displayName())
            .as("展示信息由消费方从本地库补齐，provider 不承担")
            .isNull();
        assertThat(principal.get().roles()).isEmpty();
        verify(sessionService).resolveAndSlide("tok");
    }

    @Test
    @DisplayName("空白令牌：直接 empty，不触碰会话存储")
    void rejectsBlankToken() {
        assertThat(provider.authenticate(null)).isEmpty();
        assertThat(provider.authenticate("")).isEmpty();
        assertThat(provider.authenticate("   ")).isEmpty();
        verifyNoInteractions(sessionService);
    }

    @Test
    @DisplayName("令牌无对应会话：empty（未登录）")
    void rejectsUnknownToken() {
        when(sessionService.resolveAndSlide("gone")).thenReturn(Optional.empty());

        assertThat(provider.authenticate("gone")).isEmpty();
    }

    @Test
    @DisplayName("mode() 为 local")
    void modeIsLocal() {
        assertThat(provider.mode()).isEqualTo("local");
    }
}