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

    /**
     * platform 模式的<b>代码强制代理来源证明</b>共享密钥（可选）。为空则退回“仅靠文档约定
     * 的受信反代”形态（L3 评审 CWE-290：文档边界不等于代码边界）；一旦配置，
     * {@code SessionAuthFilter} 会要求请求携带匹配的 {@link #platformSecretHeader} 头（常数时间比较），
     * 不匹配即凭据按空处理（未认证），使“实例被直连”也无法伪造身份。
     */
    private String platformSecret = "";

    /** 共享密钥所在的请求头名（反代注入，与 {@link #platformHeader} 同样必须剥离客户端自带）。 */
    private String platformSecretHeader = "X-Auth-Request-Access-Token";

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

    public String getPlatformSecret() {
        return platformSecret;
    }

    public void setPlatformSecret(String platformSecret) {
        this.platformSecret = platformSecret;
    }

    public String getPlatformSecretHeader() {
        return platformSecretHeader;
    }

    public void setPlatformSecretHeader(String platformSecretHeader) {
        this.platformSecretHeader = platformSecretHeader;
    }
}