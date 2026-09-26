package io.annona.modules.identity.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 身份模式策略（{@code annona.identity.*}）。见
 * docs/specs/2026-09-26-identity-provider-modes-adr.md。
 *
 * <p>用 {@code @Component} 注册（与 {@link SessionProperties} 同址同风格），env→属性映射写在
 * {@code application.yaml}，不散 {@code @Value}。
 */
@Component
@ConfigurationProperties(prefix = "annona.identity")
public class IdentityProperties {

    /**
     * 身份模式。取值与三个 {@code IdentityProvider} 实现的
     * {@code @ConditionalOnProperty} 一一对应；非法值在绑定期即失败，不留到运行期。
     */
    public enum Mode {
        /** 本地账号（注册/登录）。 */
        LOCAL,
        /** 平台账号：凭据取受信反代请求头。 */
        PLATFORM,
        /** 单机免登录。 */
        NONE
    }

    /** 默认 {@code local}：自部署的常规形态。 */
    private Mode mode = Mode.LOCAL;

    /**
     * platform 模式的受信请求头名。<b>信任边界</b>：反代必须剥离客户端同名头，
     * 且实例不得被直连（见 ADR §后果与约束）。
     */
    private String platformHeader = "X-Auth-Request-Email";

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getPlatformHeader() {
        return platformHeader;
    }

    public void setPlatformHeader(String platformHeader) {
        this.platformHeader = platformHeader;
    }
}