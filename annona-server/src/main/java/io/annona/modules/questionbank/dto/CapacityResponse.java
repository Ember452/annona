package io.annona.modules.questionbank.dto;

import java.util.List;

/**
 * 容量校验响应（借 🅖：按追问档位返回计数与可选标记，而非单个布尔——前端据此
 * 禁用单个选项并计算"当前题量下最多可严格保证 M 个追问"的建设性文案）。
 *
 * @param availableQuestionCount 满足难度（与追问档位 N）的可用主问题数
 * @param selectable             availableQuestionCount ≥ mainQuestionCount（硬约束判定）
 */
public record CapacityResponse(List<FollowUpOption> followUpOptions) {

    public record FollowUpOption(int followUpCount, int availableQuestionCount, boolean selectable) {
    }
}
