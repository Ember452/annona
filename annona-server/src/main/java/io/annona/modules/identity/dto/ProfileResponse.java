package io.annona.modules.identity.dto;

/**
 * 个人资料视图（GET /api/me/profile）。
 *
 * @param avatarObjectKey 当前头像对象 key；null = 未设置自定义头像
 */
public record ProfileResponse(
    String id,
    String email,
    String nickname,
    String bio,
    String timezone,
    String themeKey,
    String avatarObjectKey
) {
}
