package io.annona.modules.identity.service;

/** 登录成功后的内部结果：签发令牌与用户主键（对外由 controller 拆成 Cookie + 响应体）。 */
public record LoginOutcome(String userId, String token) {
}
