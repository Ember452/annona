package io.annona.infrastructure.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KEK 配置（{@code annona.kek.*}）。secret 的映射唯一出处在各 profile yaml
 * （dev/prod/docker 已存在 {@code secret: ${ANNONA_SECRET_KEY:}}，本类不给初值——
 * 默认 profile 下为空，使用期校验，见 AesGcmApiKeyCipher）；version 是类初值唯一
 * 出处（yaml 不列，轮换前恒 "v1"）。
 */
@ConfigurationProperties(prefix = "annona.kek")
public class KekProperties {

    /** 加密主密钥材料（环境变量注入，不入仓——AGENTS §0.2）。 */
    private String secret;

    /** 当前 KEK 版本，落 llm_provider_config.kek_version；轮换机制（annona reencrypt）落地前恒 v1。 */
    private String version = "v1";

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
