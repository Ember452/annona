package io.annona.modules.identity.provider;

import io.annona.modules.identity.dto.AuthUserResponse;
import io.annona.modules.identity.entity.AppUserEntity;
import io.annona.modules.identity.repository.AppUserRepository;
import io.annona.modules.identity.service.AuthUserRegistrar;
import io.annona.modules.identity.service.Emails;
import io.annona.modules.identity.service.IdentityProperties;
import io.annona.spi.dto.Principal;
import io.annona.spi.identity.IdentityProvider;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * {@code platform} 模式的凭据校验：受信反向代理请求头 → 本地账号（首次访问 JIT 建号）。
 *
 * <p><b>凭据来源与信任边界（本模式唯一的安全假设）</b>：反代在完成统一登录后注入
 * {@code annona.identity.platform-header}（默认 {@code X-Auth-Request-Email}），并且<b>必须剥离</b>
 * 客户端自带的同名头；实例<b>不得</b>被直连。任一条件不满足，客户端即可伪造任意身份——因此本类
 * 构造时打 WARN，把这条约束留在运行日志里（另见 identity-provider-modes ADR）。
 *
 * <p><b>为什么按 email 匹配而非建 identity_link 表</b>：v1 只有一个外部身份源，且设计文档 §5.3 的
 * 7 张身份表里没有 link 表；新增表要 V2 迁移 + 实体 + 仓储，收益为零。触发重新评估的条件
 * （出现第二个身份源 / 托管版 OAuth）写进 ADR。
 */
@Component
@ConditionalOnProperty(prefix = "annona.identity", name = "mode", havingValue = "platform")
public class PlatformIdentityProvider implements IdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(PlatformIdentityProvider.class);

    private final AppUserRepository userRepository;
    private final AuthUserRegistrar registrar;

    public PlatformIdentityProvider(AppUserRepository userRepository,
                                    AuthUserRegistrar registrar,
                                    IdentityProperties properties) {
        this.userRepository = userRepository;
        this.registrar = registrar;
        log.warn("identity.mode=platform 已启用：凭据取自受信反代请求头 '{}'。"
                + "部署前提——反代必须剥离客户端同名头，且本实例不得被直连；否则任意客户端可伪造身份。",
            properties.getPlatformHeader());
    }

    @Override
    public String mode() {
        return "platform";
    }

    @Override
    public Optional<Principal> authenticate(String credentialToken) {
        String email = Emails.normalize(credentialToken);
        if (!Emails.isValid(email)) {
            // 头缺失或为垃圾值：绝不据此建号（否则一次扫描就能污染 app_user）
            return Optional.empty();
        }
        Optional<AppUserEntity> existing = userRepository.findActiveByEmail(email);
        if (existing.isPresent()) {
            return Optional.of(toPrincipal(existing.get()));
        }
        try {
            AuthUserResponse created = registrar.provisionExternal(email);
            return Optional.of(new Principal(created.id(), created.email(), Set.of(created.role())));
        } catch (DataIntegrityViolationException e) {
            // 并发首访：另一请求刚插入同一邮箱。provisionExternal 的事务已标记回滚，无法在其内部回读，
            // 故在此（非事务上下文）重查一次。
            return userRepository.findActiveByEmail(email).map(this::toPrincipal);
        }
    }

    private Principal toPrincipal(AppUserEntity user) {
        return new Principal(user.getId().toString(), user.getEmail(), Set.of(user.getRole()));
    }
}