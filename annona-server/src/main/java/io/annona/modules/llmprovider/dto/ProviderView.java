package io.annona.modules.llmprovider.dto;

/**
 * Provider 配置视图（对外唯一形状）：只有掩码，无密文列——KEK ADR §决策 5 的
 * "明文 Key 永不下发前端"在此列结构上成立。
 *
 * @param id           配置 ID
 * @param providerKey  供应商标识
 * @param baseUrl      自定义端点（可空）
 * @param purpose      六用途之一
 * @param maskedApiKey 掩码（首4尾2）
 * @param defaultModel 默认模型（可空）
 * @param enabled      开关
 */
public record ProviderView(String id, String providerKey, String baseUrl, String purpose,
                           String maskedApiKey, String defaultModel, boolean enabled) {
}
