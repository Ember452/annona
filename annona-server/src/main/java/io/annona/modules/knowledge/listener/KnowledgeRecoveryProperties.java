package io.annona.modules.knowledge.listener;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 恢复调度参数（{@code annona.knowledge.recovery.*}，全部默认值借 🅖 实测：
 * PENDING 10m 投递丢失 / 在途 15m 心跳丢失 / 上限 3 次 / 间隔 60s）。
 */
@ConfigurationProperties(prefix = "annona.knowledge.recovery")
public class KnowledgeRecoveryProperties {

    /** 调度开关（docker IT 的精简上下文可关）。 */
    private boolean enabled = true;

    /** PENDING 超过此时长未推进 = 投递丢失，补投并累加恢复计数。 */
    private Duration pendingThreshold = Duration.ofMinutes(10);

    /** 在途状态超过此时长无心跳 = 消费者崩溃，条件重置回 PENDING。 */
    private Duration processingThreshold = Duration.ofMinutes(15);

    /** PENDING 恢复计数上限；达限转 FAILED，提示手动重试（re-vectorize 清零）。 */
    private int maxRecoveryCount = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPendingThreshold() {
        return pendingThreshold;
    }

    public void setPendingThreshold(Duration pendingThreshold) {
        this.pendingThreshold = pendingThreshold;
    }

    public Duration getProcessingThreshold() {
        return processingThreshold;
    }

    public void setProcessingThreshold(Duration processingThreshold) {
        this.processingThreshold = processingThreshold;
    }

    public int getMaxRecoveryCount() {
        return maxRecoveryCount;
    }

    public void setMaxRecoveryCount(int maxRecoveryCount) {
        this.maxRecoveryCount = maxRecoveryCount;
    }
}
