package io.annona.modules.identity.provider;

import io.annona.spi.dto.Principal;
import io.annona.spi.identity.IdentityProvider;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code none} 模式的凭据校验：单机免登录，任何请求都认定为固定 bootstrap 用户。
 *
 * <p><b>为什么是固定 UUID 而非 {@code id=local}</b>：{@code app_user.id} 是 UUID 主键，
 * {@code direction} 等业务表的外键引用它；字面量 {@code "local"} 无法落库。设计文档 §5.3 里
 * 的 "bootstrap {@code id=local}" 只是口语，本类与 {@link NoneModeUserBootstrapper} 一起把它
 * 落成常量 {@link #LOCAL_USER_ID}。
 *
 * <p>本实现不看凭据、不碰 DB（保持过滤器廉价）；该用户行由启动期 bootstrap 保证存在。
 */
@Component
@ConditionalOnProperty(prefix = "annona.identity", name = "mode", havingValue = "none")
public class NoneIdentityProvider implements IdentityProvider {

    /** 单机 bootstrap 用户的固定主键；业务表外键指向它。 */
    public static final UUID LOCAL_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** bootstrap 用户邮箱（{@code .local} 为保留 TLD，不会与真实注册邮箱冲突）。 */
    public static final String BOOTSTRAP_EMAIL = "local@annona.local";

    @Override
    public String mode() {
        return "none";
    }

    @Override
    public Optional<Principal> authenticate(String credentialToken) {
        return Optional.of(new Principal(LOCAL_USER_ID.toString(), "Local User", Set.of("USER")));
    }
}