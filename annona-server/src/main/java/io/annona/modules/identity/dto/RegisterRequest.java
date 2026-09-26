package io.annona.modules.identity.dto;

/**
 * 注册请求。校验在 service 层做（P1a-01 未引入 spring-boot-starter-validation，避免顺手扩面；
 * 待前端表单联调若需要统一 @Valid，再另立任务）。
 *
 * @param email    邮箱
 * @param password 明文口令（仅内存中哈希，绝不落库/落日志）
 */
public record RegisterRequest(String email, String password) {

    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=<redacted>]";
    }
}
