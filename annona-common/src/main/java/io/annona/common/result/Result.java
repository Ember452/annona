package io.annona.common.result;

import io.annona.common.exception.ErrorCode;
import org.slf4j.MDC;

/**
 * 统一响应结构：所有对外接口（成功或业务失败）都以本类型返回，HTTP 状态码固定 200。
 *
 * <p>字段：
 * <ul>
 *   <li>{@code code}：业务码。{@code 0} 表示成功；其他值参见 {@link io.annona.common.exception.ErrorCode}。</li>
 *   <li>{@code message}：面向用户的可读文案。</li>
 *   <li>{@code data}：泛型业务负载；失败时为 {@code null}。</li>
 *   <li>{@code traceId}：请求链路 ID（来自 SLF4J MDC 的 {@code traceId} 键，P0-05 observability
 *       里由过滤器注入）；用于用户反馈 → 日志定位。MDC 未设置时为 {@code null}。</li>
 * </ul>
 *
 * <p>本类不可变，构造仅通过静态工厂。
 */
public final class Result<T> {

    /** 业务成功的固定码；{@link #isSuccess()} 依此判定。 */
    public static final int SUCCESS_CODE = 0;

    private final int code;
    private final String message;
    private final T data;
    private final String traceId;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = currentTraceId();
    }

    // ========== 成功 ==========

    public static <T> Result<T> success() {
        return new Result<>(SUCCESS_CODE, "ok", null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, "ok", data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(SUCCESS_CODE, message, data);
    }

    // ========== 失败 ==========

    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    // ========== 判定 ==========

    public boolean isSuccess() {
        return code == SUCCESS_CODE;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getTraceId() {
        return traceId;
    }

    /**
     * 从 SLF4J MDC 读取 traceId。用反射式软依赖避免 common 强制绑定 logback：
     * slf4j-api 已在 classpath（Spring Boot 默认引入），直接调用即可。
     */
    private static String currentTraceId() {
        try {
            return MDC.get("traceId");
        } catch (Throwable ignored) {
            // MDC 未初始化或 SLF4J 缺失时返回 null，不影响响应主流程
            return null;
        }
    }
}
