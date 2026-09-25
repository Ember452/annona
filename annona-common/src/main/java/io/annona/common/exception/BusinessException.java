package io.annona.common.exception;

/**
 * 业务异常。annona 全项目内<b>所有</b>可预期失败必须通过本类型抛出，禁止 {@code throw new RuntimeException}。
 *
 * <p>HTTP 层由 {@link GlobalExceptionHandler} 统一兜底：返回 HTTP 200 +
 * {@code Result.error(code, message)}；堆栈仅进日志，不外泄。
 *
 * <p>构造规范：
 * <ul>
 *   <li>推荐：{@code new BusinessException(ErrorCode.XXX, "面向用户的具体描述")}——
 *       {@code message} 会覆盖 {@code ErrorCode} 的默认文案，用于把上下文塞进错误提示。</li>
 *   <li>兜底：仅传 {@code ErrorCode} 时使用其默认 message。</li>
 *   <li>带因：传 {@code cause} 时保留原始栈供日志。</li>
 * </ul>
 */
public class BusinessException extends RuntimeException {

    private final int code;
    private final String message;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
        this.message = message;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
