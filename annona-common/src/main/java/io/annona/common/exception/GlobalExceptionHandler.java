package io.annona.common.exception;

import io.annona.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常兜底：所有未处理的异常一律转换为 HTTP 200 + {@code Result.error(...)}，
 * 堆栈只进日志不外泄。这是 annona 对外接口的<b>唯一</b>异常出口，
 * 业务代码禁止在 Controller 里写 try/catch 返 Result。
 *
 * <p>具体业务分支的处理器（如参数校验、AI 服务网络异常）在需要时按需追加；
 * P0-03 只落地两条件必须规则：
 * <ol>
 *   <li>业务可预期失败：{@link BusinessException} → 携带的 {@code code + message} 直接透出。</li>
 *   <li>未知异常兜底：任何其他 {@link Throwable} → 固定 {@code INTERNAL_ERROR}，用户侧只看到"系统繁忙"，
 *       服务端 ERROR 级日志保留完整栈。</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Throwable.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleUnknown(Throwable t) {
        // 异常必须作为最后一个参数传入，SLF4J 会自动附栈；不对外暴露栈与消息内容
        log.error("未预期异常", t);
        return Result.error(ErrorCode.INTERNAL_ERROR, "系统繁忙，请稍后重试");
    }
}
