package io.annona.modules.identity.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.spi.dto.Principal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link NoneIdentityProvider}：免登录恒返回固定 bootstrap 主体（纯逻辑，本机跑）。 */
@DisplayName("NoneIdentityProvider：免登录固定主体")
class NoneIdentityProviderTest {

    private final NoneIdentityProvider provider = new NoneIdentityProvider();

    @Test
    @DisplayName("任意凭据（含 null）都返回固定 UUID 主体，且不看凭据")
    void alwaysReturnsBootstrapUser() {
        for (String credential : new String[] {null, "", "anything"}) {
            Optional<Principal> principal = provider.authenticate(credential);
            assertThat(principal).isPresent();
            assertThat(principal.get().id()).isEqualTo(NoneIdentityProvider.LOCAL_USER_ID.toString());
            assertThat(principal.get().roles()).containsExactly("USER");
        }
    }

    @Test
    @DisplayName("固定主键是合法 UUID（业务表外键能指向它）")
    void bootstrapIdIsUuid() {
        assertThat(NoneIdentityProvider.LOCAL_USER_ID.toString())
            .isEqualTo("00000000-0000-0000-0000-000000000001");
    }

    @Test
    @DisplayName("mode() 为 none")
    void modeIsNone() {
        assertThat(provider.mode()).isEqualTo("none");
    }
}