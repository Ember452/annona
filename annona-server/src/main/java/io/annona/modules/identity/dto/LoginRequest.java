package io.annona.modules.identity.dto;

/** 登录请求。覆写 toString 屏蔽明文口令（record 默认 toString 会带出 password）。 */
public record LoginRequest(String email, String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=<redacted>]";
    }
}
