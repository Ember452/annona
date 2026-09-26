package io.annona.common.exception;

/**
 * annona 业务错误码。HTTP 状态码固定 200，业务失败靠本枚举的 {@code code} 区分。
 *
 * <p>分段规则（与 annona 模块一一对应，新增模块时按顺序续段，不复用历史段号）：
 * <ul>
 *   <li>1000–1099：通用（请求/权限/系统兜底）</li>
 *   <li>1100–1199：AI 与模型调用</li>
 *   <li>1200–1299：限流与配额</li>
 *   <li>2000+：按业务模块分段（P1 起在对应模块任务里扩展）</li>
 * </ul>
 *
 * <p>P0-03 只落通用与 AI 两组；其余模块的错误码在各自阶段任务落地时追加，
 * 禁止提前预留空位（YAGNI）。
 */
public enum ErrorCode {

    // ========== 通用 1000–1099 ==========
    INTERNAL_ERROR(1000, "服务器内部错误"),
    BAD_REQUEST(1001, "请求参数错误"),
    NOT_FOUND(1002, "资源不存在"),
    METHOD_NOT_ALLOWED(1003, "请求方法不支持"),
    UNAUTHORIZED(1004, "未授权"),
    FORBIDDEN(1005, "禁止访问"),
    DATA_CONFLICT(1006, "数据冲突或重复提交，请刷新后重试"),

    // ========== AI 与模型 1100–1199 ==========
    AI_SERVICE_UNAVAILABLE(1100, "AI 服务暂时不可用，请稍后重试"),
    AI_SERVICE_TIMEOUT(1101, "AI 服务响应超时"),
    AI_SERVICE_ERROR(1102, "AI 服务调用失败"),
    AI_API_KEY_INVALID(1103, "AI 服务密钥无效"),

    // ========== 限流与配额 1200–1299 ==========
    RATE_LIMIT_EXCEEDED(1200, "请求过于频繁，请稍后再试"),

    // ========== identity 2000–2099（P1a-01） ==========
    EMAIL_ALREADY_REGISTERED(2001, "该邮箱已被注册"),
    INVALID_CREDENTIALS(2002, "邮箱或密码不正确"),
    ACCOUNT_LOCKED(2003, "登录失败次数过多，账号已临时锁定，请稍后再试"),
    SESSION_EXPIRED(2004, "登录状态已失效，请重新登录"),

    // ========== direction 2100–2199（P1a-03，shared 主数据） ==========
    DIRECTION_NOT_FOUND(2100, "方向不存在"),
    DIRECTION_KEY_DUPLICATE(2101, "同名方向已存在"),
    DIRECTION_LIMIT_REACHED(2102, "自定义方向已达上限（200），请先归档或合并");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
