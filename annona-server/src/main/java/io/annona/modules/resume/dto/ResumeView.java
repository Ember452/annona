package io.annona.modules.resume.dto;

import io.annona.modules.resume.model.ResumeAnalysis;

/**
 * 简历视图（列表/详情共用）。id/时间 String 口径（interview/knowledge 同款）。
 * 处理中 {@code analysis} 为 null；DONE 后非空。
 */
public record ResumeView(String id, String name, String status, ResumeAnalysis analysis,
                         String error, String createdAt) {
}
