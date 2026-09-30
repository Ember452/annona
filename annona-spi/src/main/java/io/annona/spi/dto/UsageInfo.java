package io.annona.spi.dto;

/**
 * 一次模型调用的用量。字段以 provider 返回的 token 计数为准；provider 不返回时填 0，
 * 记账侧对 total=0 的调用不记行（宁缺毋假账，llmprovider-metering-adr 否决表）。
 *
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 */
public record UsageInfo(int promptTokens, int completionTokens) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
