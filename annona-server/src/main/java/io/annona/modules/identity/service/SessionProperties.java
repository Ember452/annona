package io.annona.modules.identity.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会话策略（{@code annona.session.*}）。identity ADR §决策 §3：活跃令牌 7 天滑动。
 * 用 {@code @Component} 注册（Spring Boot 会自动绑定 {@code @ConfigurationProperties}），
 * env→属性映射写在 {@code application.yaml}，不散 {@code @Value}（AGENTS §Backend）。
 */
@Component
@ConfigurationProperties(prefix = "annona.session")
public class SessionProperties {

    /** 会话 TTL（滑动）。 */
    private Duration ttl = Duration.ofDays(7);
    /** 会话 Cookie 名（HttpOnly）。 */
    private String cookie = "ANNONA_SESSION";
    /**
     * Cookie 的 Secure 标志。默认 false 以保住“clone 下来 http 就能跑”；
     * TLS 部署（prod / 反代 https）必须置 true，否则会话 Cookie 可被明文信道传输。
     */
    private boolean cookieSecure = false;

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public String getCookie() {
        return cookie;
    }

    public void setCookie(String cookie) {
        this.cookie = cookie;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }
}
