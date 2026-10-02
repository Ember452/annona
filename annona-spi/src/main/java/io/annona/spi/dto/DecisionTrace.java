package io.annona.spi.dto;

/**
 * 一条规则命中后的留痕。落到 {@code decision_trace} 表，可解释面板据此展示"凭什么这么问"。
 *
 * <p>字段取值集合以生产方为准，不要按字面猜：{@code ruleKey} 见
 * {@code DecisionRule#key()} 与 {@code GuardEngine} 的保护常量，{@code action} 见下方枚举。
 *
 * @param ruleKey  规则标识：{@code FORGETTING_CURVE / WEAK_DIRECTION}（调整型规则）或
 *                 {@code SAMPLE_GUARD / VERSION_BASELINE / SELF_REPORTED / NO_STUDY_RECORD /
 *                 REMIND_REVIEW}（guard 与提醒留痕，由 {@code GuardEngine}/{@code advisor} 产出）
 * @param action   本次留痕的动作：{@code RAISE_DIFFICULTY / LOWER_DIFFICULTY_REVIEW /
 *                 NO_ADJUST / BASELINE_ONLY / EXCLUDE_LEARNING_SIGNAL / INTERVIEW_ONLY /
 *                 SUGGEST_REVIEW / REVIEW_NONE_PACKED}
 * @param reason   面向用户的原因文案；禁止为空
 * @param rejectedBy <b>语义位</b>：原计划“被下游保护规则否决时记否决者 ruleKey”（ADR 决策 5），
 *                 实际 guard 是<b>前置统一拦截</b>（拦下时规则根本不执行），因此全仓内除用户
 *                 驳回写入 {@code "USER"} 外没有任何生产者。保留字段是为了以后真要逐规则
 *                 否决时不破列结构（planner-decision-kernel-adr 修订 2）；外部实现方不得
 *                 依赖它能取到规则键
 */
public record DecisionTrace(String ruleKey, String action, String reason, String rejectedBy) {

    public static DecisionTrace accepted(String ruleKey, String action, String reason) {
        return new DecisionTrace(ruleKey, action, reason, null);
    }
}
