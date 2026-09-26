package io.annona.shared.direction.dto;

/**
 * 绑定知识库文档（USER_CUSTOM → KNOWLEDGE_BASE 单向升级，不提供解绑）。
 *
 * @param kbDocId kb_doc 主键；kb_doc 表 P1a-05 才建，本期只做 UUID 格式校验
 */
public record BindKbDocRequest(String kbDocId) {
}
