package io.annona.modules.usage.dto;

import java.util.List;

/**
 * 会话用量视图（GET /api/usage/session/{id}）。
 *
 * @param sessionId 会话
 * @param items     按模型聚合的用量行
 * @param priced    单价表是否配置（false → estimatedCost 全 null，前端显示"未配置单价"）
 */
public record SessionUsageView(String sessionId, List<UsageItemView> items, boolean priced) {

    /**
     * @param model            模型名
     * @param promptTokens     输入 token 合计
     * @param completionTokens 输出 token 合计
     * @param calls            调用次数
     * @param estimatedCost    估算成本（每 1K token 单价 × 量；无单价配置为 null——
     *                         宁可不显示，不给假数）
     */
    public record UsageItemView(String model, long promptTokens, long completionTokens,
                                long calls, Double estimatedCost) {
    }
}
