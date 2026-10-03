package io.annona.modules.identity.dto;

/**
 * PATCH /api/me/profile 请求体（部分更新；null = 不动）。头像不经本端点改（走 /api/me/avatar）。
 */
public record UpdateProfileRequest(String nickname, String bio) {
}
