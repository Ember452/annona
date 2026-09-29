package io.annona.modules.usage.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 模型用量账（V11 token_usage）。行只增不改——记账走原生 INSERT（Repository），
 * 本实体存在是为了列表/聚合读的类型安全与 JPA 一致性边界。
 * scene/purpose/session_id 的口径与可空性见 V11 列注释（llmprovider-metering-adr）。
 */
@Entity
@Table(name = "token_usage")
public class TokenUsageEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "scene", nullable = false)
    private String scene;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "purpose", nullable = false)
    private String purpose;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "prompt_hash")
    private String promptHash;

    @Column(name = "evaluator_version")
    private String evaluatorVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
        columnDefinition = "timestamptz")
    private Instant createdAt;

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

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }

    public String getPromptHash() {
        return promptHash;
    }

    public void setPromptHash(String promptHash) {
        this.promptHash = promptHash;
    }

    public String getEvaluatorVersion() {
        return evaluatorVersion;
    }

    public void setEvaluatorVersion(String evaluatorVersion) {
        this.evaluatorVersion = evaluatorVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
