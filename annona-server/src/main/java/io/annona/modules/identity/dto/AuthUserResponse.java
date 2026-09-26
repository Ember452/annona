package io.annona.modules.identity.dto;

/** 对外的用户视图（不含任何口令字段）。 */
public record AuthUserResponse(String id, String email, String role) {
}
