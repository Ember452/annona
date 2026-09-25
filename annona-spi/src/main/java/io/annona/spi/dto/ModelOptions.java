package io.annona.spi.dto;

/**
 * 模型调用可选参数。字段允许为 {@code null}，由 Provider 使用各自默认值。
 *
 * @param model       具体模型名（如 {@code qwen-plus}）；{@code null} 时由 Provider 决定
 * @param temperature 采样温度；{@code null} 用 Provider 默认
 * @param maxTokens   单次响应最大 token；{@code null} 用 Provider 默认
 */
public record ModelOptions(String model, Double temperature, Integer maxTokens) {

    /** 只指定模型，其他参数走 Provider 默认。 */
    public static ModelOptions of(String model) {
        return new ModelOptions(model, null, null);
    }

    /** 全部使用 Provider 默认。 */
    public static ModelOptions defaults() {
        return new ModelOptions(null, null, null);
    }
}
