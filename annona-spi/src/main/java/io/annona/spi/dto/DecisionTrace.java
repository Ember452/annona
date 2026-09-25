package io.annona.spi.dto;

/**
 * 一条规则命中后的留痕。落到 {@code decision_trace} 表，可解释面板据此展示"凭什么这么问"。
 *
 * @param ruleKey  规则标识（如 {@code FORGETTING_CURVE / WEAK_DIRECTION / SAMPLE_GUARD}）
 * @param action   本规则施加的动作（如 {@code RAISE_DIFFICULTY / CAP_DIRECTION}）
 * @param reason   面向用户的原因文案；禁止为空
 * @param rejectedBy 若本规则被下游保护规则否决，记录否决者的 ruleKey；未否决时为 {@code null}
 */
public record DecisionTrace(String ruleKey, String action, String reason, String rejectedBy) {

    public static DecisionTrace accepted(String ruleKey, String action, String reason) {
        return new DecisionTrace(ruleKey, action, reason, null);
    }
}
