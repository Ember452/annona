package io.annona.modules.identity.provider;

import io.annona.modules.identity.service.AuthUserRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code none}（单机免登录）模式的启动 bootstrap：保证固定 bootstrap 用户与其 {@code user_profile} 存在。
 *
 * <p>为什么需要它：{@code NoneIdentityProvider} 直接返回固定 UUID 作为主体，而 {@code direction} 等
 * 业务表以该 UUID 为外键、当前用户解析器也会按该 id 回查本地库——行不存在时首个请求就会失败。
 *
 * <p>为什么放启动期而非首次请求惰性创建：启动即就绪，消除首个请求的建号竞态；建号失败会在启动
 * 日志里暴露，而不是变成一次用户可见的 500。幂等性由 {@code AuthUserRegistrar} 保证。
 */
@Component
@ConditionalOnProperty(prefix = "annona.identity", name = "mode", havingValue = "none")
public class NoneModeUserBootstrapper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NoneModeUserBootstrapper.class);

    private final AuthUserRegistrar registrar;

    public NoneModeUserBootstrapper(AuthUserRegistrar registrar) {
        this.registrar = registrar;
    }

    @Override
    public void run(ApplicationArguments args) {
        registrar.ensureLocalBootstrap(NoneIdentityProvider.LOCAL_USER_ID, NoneIdentityProvider.BOOTSTRAP_EMAIL);
        log.info("identity.mode=none：bootstrap 用户就绪（id={}, email={}）",
            NoneIdentityProvider.LOCAL_USER_ID, NoneIdentityProvider.BOOTSTRAP_EMAIL);
    }
}