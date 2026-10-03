package io.annona.modules.identity.dto;

/**
 * 头像上传/回滚响应。
 *
 * @param success     恒 true（失败经 Result.error 分支返回，不到本体）
 * @param previousKey 被替换下来的旧头像 key（上传时）或回滚后的当前 key；可能为 null（首次设置）
 */
public record AvatarUploadResponse(boolean success, String previousKey) {
}
