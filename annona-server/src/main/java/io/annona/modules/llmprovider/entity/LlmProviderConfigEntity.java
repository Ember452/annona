package io.annona.modules.llmprovider.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Provider 配置行（V10 llm_provider_config）。密文三列原样映射（nonce/cipher/kek_version，
 * KEK ADR §决策 2）——本实体<b>没有</b>存放明文 Key 的字段，这是结构性的：想在业务代码里
 * 拿到明文，必须先显式调用 decrypt，评审时一眼可见。
 */
@Entity
@Table(name = "llm_provider_config")
public class LlmProviderConfigEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "provider_key", nullable = false)
    private String providerKey;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "purpose", nullable = false)
    private String purpose;

    @Column(name = "api_key_nonce", nullable = false)
    private byte[] apiKeyNonce;

    @Column(name = "api_key_cipher", nullable = false)
    private byte[] apiKeyCipher;

    @Column(name = "kek_version", nullable = false)
    private String kekVersion;

    @Column(name = "api_key_masked", nullable = false)
    private String apiKeyMasked;

    @Column(name = "default_model")
    private String defaultModel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
        columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public void setProviderKey(String providerKey) {
        this.providerKey = providerKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public byte[] getApiKeyNonce() {
        return apiKeyNonce;
    }

    public void setApiKeyNonce(byte[] apiKeyNonce) {
        this.apiKeyNonce = apiKeyNonce;
    }

    public byte[] getApiKeyCipher() {
        return apiKeyCipher;
    }

    public void setApiKeyCipher(byte[] apiKeyCipher) {
        this.apiKeyCipher = apiKeyCipher;
    }

    public String getKekVersion() {
        return kekVersion;
    }

    public void setKekVersion(String kekVersion) {
        this.kekVersion = kekVersion;
    }

    public String getApiKeyMasked() {
        return apiKeyMasked;
    }

    public void setApiKeyMasked(String apiKeyMasked) {
        this.apiKeyMasked = apiKeyMasked;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
