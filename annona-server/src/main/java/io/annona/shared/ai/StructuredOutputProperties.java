package io.annona.shared.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 结构化输出重试配置。默认值只在 yaml 出口（application.yaml ${ANNONA_STRUCTURED_MAX_ATTEMPTS:3}），
 * 字段不给初值（AGENTS §4 配置键单源规则）。3 次是上游同款起点：多数字形错误第二次带反馈即修复。
 */
@ConfigurationProperties(prefix = "annona.ai.structured-output")
public class StructuredOutputProperties {

    /** 解析失败带反馈重试的最大次数（含首次）。 */
    private Integer maxAttempts;

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(Integer maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
