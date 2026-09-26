package io.annona.modules.identity.provider;

import io.annona.modules.identity.service.SessionService;
import io.annona.spi.dto.Principal;
import io.annona.spi.identity.IdentityProvider;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code local} 模式的凭据校验：会话 Cookie 令牌 → userId（命中即滑动续期）。
 *
 * <p>{@code displayName} 为 {@code null} 是<b>有意</b>的：会话只携带 userId，展示信息（邮箱、昵称）
 * 由消费方 {@code CurrentPrincipalArgumentResolver} + {@code UserQueryService} 从本地库补齐。
 * provider 只回答"这是谁"，不承担"他长什么样"——否则静态资源与心跳也会多一次 DB 往返。
 *
 * <p>{@code matchIfMissing = true}：不配 {@code annona.identity.mode} 时默认本地账号，
 * 与 {@link io.annona.modules.identity.service.IdentityProperties} 的默认值一致。
 */
@Component
@ConditionalOnProperty(prefix = "annona.identity", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalIdentityProvider implements IdentityProvider {

    private final SessionService sessionService;

    public LocalIdentityProvider(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public String mode() {
        return "local";
    }

    @Override
    public Optional<Principal> authenticate(String credentialToken) {
        if (credentialToken == null || credentialToken.isBlank()) {
            return Optional.empty();
        }
        return sessionService.resolveAndSlide(credentialToken)
            .map(userId -> new Principal(userId, null, Set.of()));
    }
}