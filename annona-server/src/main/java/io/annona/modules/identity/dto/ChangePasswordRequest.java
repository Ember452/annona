package io.annona.modules.identity.dto;

/** 改密请求：验旧口令、设新口令（新口令长度同注册，≥8）。 */
public record ChangePasswordRequest(String oldPassword, String newPassword) {
}
