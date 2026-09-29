package io.annona.modules.llmprovider.dto;

/**
 * 新建/编辑 Provider 请求体。
 *
 * @param providerKey  供应商标识（必填）
 * @param baseUrl      OpenAI 兼容根地址（可空）
 * @param purpose      用途（必须 ∈ LlmProviderService.PURPOSES）
 * @param apiKey       明文 Key；新建必填，编辑留空=保留原密文
 * @param defaultModel 默认模型
 * @param enabled      开关（null 视为 true）
 */
public record SaveProviderRequest(String providerKey, String baseUrl, String purpose,
                                  String apiKey, String defaultModel, Boolean enabled) {
}
