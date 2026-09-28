package io.annona.spi.dto;

import java.util.Locale;

/**
 * 检索通道开关（P1a-07/P1a-09）。
 *
 * <p>存在的理由是评测要归因：出口条件③要的是"混合 vs 纯向量"的实测差值，
 * 所以三档必须都能单独跑出来，而不是只有混合一条路（retrieval-hybrid-adr §决策 3）。
 *
 * <p>{@link #KEYWORD} 只走关键词通道，用来回答"tsv + trgm 自己能不能撑起召回"；
 * 它不用于线上问答，线上默认 {@link #BOTH}。
 */
public enum RetrievalMode {

    /** 语义 + 关键词双通道，经 RRF 融合（默认）。 */
    BOTH,

    /** 只走 pgvector 语义通道，即评测里的纯向量对照组。 */
    SEMANTIC,

    /** 只走关键词通道（tsv 主，命中 0 行才用 pg_trgm 包含匹配兜底）。 */
    KEYWORD;

    /**
     * 宽松解析：null/空白当作默认档 {@link #BOTH}，非法值直接抛
     * {@link IllegalArgumentException}（由调用方转业务错误码，不做静默兜底）。
     */
    public static RetrievalMode parseOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return BOTH;
        }
        return RetrievalMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
