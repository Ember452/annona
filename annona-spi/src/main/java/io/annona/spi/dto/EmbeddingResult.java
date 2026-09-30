package io.annona.spi.dto;

import java.util.List;

/**
 * 一次嵌入调用的结果：向量列表 + 供应商返回的用量。
 *
 * <p>usage 口径与 {@link ModelResponse} 一致：provider 返回的 token 计数为准，
 * 不返回时填 0（<b>不做字符估算</b>——假账比缺账毒，llmprovider-metering-adr 否决表）。
 * 批量调用被实现方内部分批时，usage 为各批<b>累加</b>后的总量。
 *
 * @param vectors 与入参一一对应、等长等维的向量（契约同 {@code embed} 旧返回）
 * @param usage   本次调用的 token 用量（embedding 只有输入侧，completionTokens 恒 0）
 */
public record EmbeddingResult(List<float[]> vectors, UsageInfo usage) {
}
