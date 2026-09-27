package io.annona.modules.knowledge.dto;

/**
 * 分块预览项（READY 后用户肉眼校验分块质量的第一手数据，决策清单 B-10）。
 *
 * @param index       分块序号
 * @param headingPath 标题路径（"章 > 节"），无标题为空串
 * @param charStart   清洗后原文起始偏移（含）
 * @param charEnd     结束偏移（不含）
 * @param content     分块正文（含标题路径前缀行，与 embedding 输入一致）
 */
public record KbDocChunkView(int index, String headingPath, int charStart, int charEnd, String content) {
}
