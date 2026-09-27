package io.annona.common.parse;

/**
 * 解析产出的结构化块 IR——分块器（P1a-06）的唯一输入形状（knowledge-ingestion-adr §决策 1）。
 *
 * <p>上游三仓都没有这一层（🅖 输出纯文本、🅢 输出裸 string 数组），为 annona 自建：
 * 引用跳回原文段落（P1a-08）、关键词通道标题加权（P1a-07）、analyzer_version 升级只重分块
 * 不重解析，都靠块级结构与偏移支撑。
 *
 * @param type      块类型；解析实现把不认识的形态降级为 {@code PARAGRAPH}
 * @param level     标题层级 1–6；仅 {@code HEADING} 有值，其余为 {@code null}
 * @param text      块文本；已去首尾空白且非空白（解析实现负责，分块器防御性跳过空白块）
 * @param charStart 块在清洗后全文中的起始偏移（含）
 * @param charEnd   块在清洗后全文中的结束偏移（不含）
 */
public record DocumentBlock(BlockType type, Integer level, String text, int charStart, int charEnd) {

    public enum BlockType { HEADING, PARAGRAPH, LIST_ITEM, TABLE, CODE }
}
