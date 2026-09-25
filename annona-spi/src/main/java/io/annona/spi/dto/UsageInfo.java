package io.annona.spi.dto;

/**
 * 一次模型调用的用量。字段以 provider 返回的 token 计数为准；provider 不返回时填 0，
 * 由 usage 计量侧兜底走字符估算。
 *
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 */
public record UsageInfo(int promptTokens, int completionTokens) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
