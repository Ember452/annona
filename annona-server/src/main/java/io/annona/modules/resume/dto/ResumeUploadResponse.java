package io.annona.modules.resume.dto;

import java.util.UUID;

/** 简历上传响应（POST /api/resume/upload）。{@code duplicate}=true 表示命中同用户同内容幂等，未新起分析。 */
public record ResumeUploadResponse(UUID id, boolean duplicate, String status, String message) {
}
