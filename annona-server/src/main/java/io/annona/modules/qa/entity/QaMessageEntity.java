package io.annona.modules.qa.entity;

import io.annona.modules.qa.dto.QaCitation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * qa_message 消息（V6 §2；qa-streaming-adr §决策 4/8）。生命周期：ASSISTANT 行先落
 * 空占位（completed=false），流结束一次性回填（非增量追加，借 🅖）；客户端断线保留
 * 已生成部分并保持 completed=false。
 *
 * <p>citations 用 Hibernate JSON 映射（{@link SqlTypes#JSON}）：V6 列为 JSONB，
 * 往返正确性由 QaFlowIT 在真 PG 上证伪；受阻降级 TEXT + 显式转换器（ADR §重新评估）。
 */
@Entity
@Table(name = "qa_message")
public class QaMessageEntity {

    public static final String TYPE_USER = "USER";
    public static final String TYPE_ASSISTANT = "ASSISTANT";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /** 会话内序号（应用侧 max+1 生成）；唯一约束 (session_id, message_order) 兜底并发。 */
    @Column(name = "message_order", nullable = false)
    private int messageOrder;

    @Column(name = "type", nullable = false, length = 16)
    private String type;

    /** ASSISTANT 占位阶段为空串。 */
    @Column(name = "content", nullable = false)
    private String content = "";

    /** true = 完整回答；追问组装上下文只取 completed 消息（借 🅖）。 */
    @Column(name = "completed", nullable = false)
    private boolean completed = true;

    /** 结构化引用；仅 ASSISTANT 行持有（DB CHECK 兜底），USER 行恒为 null。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations")
    private List<QaCitation> citations;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public int getMessageOrder() {
        return messageOrder;
    }

    public void setMessageOrder(int messageOrder) {
        this.messageOrder = messageOrder;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public List<QaCitation> getCitations() {
        return citations;
    }

    public void setCitations(List<QaCitation> citations) {
        this.citations = citations;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
