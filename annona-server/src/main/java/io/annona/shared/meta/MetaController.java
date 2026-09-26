package io.annona.shared.meta;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.result.Result;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用元信息接口。<b>P0-03 阶段的验收探针</b>：确认 {@link Result} 结构与
 * {@link io.annona.common.exception.GlobalExceptionHandler} 是否按契约生效。
 *
 * <p>三个端点分别覆盖三条出口条件：
 * <ol>
 *   <li>{@code GET /api/meta/ping} → {@code Result.success("pong")}（成功路径）</li>
 *   <li>{@code GET /api/meta/error/business} → 抛 {@link BusinessException}，
 *       由 {@link io.annona.config.web.GlobalExceptionHandler} 转 {@code Result.error(code, message)}（业务失败，HTTP 200）</li>
 *   <li>{@code GET /api/meta/error/unknown} → 抛未预期 {@link RuntimeException}，
 *       由同一处理器兜底为 {@code INTERNAL_ERROR} + <b>HTTP 500</b>，响应体不含栈（未知异常）</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/meta")
// 错误探针不得出现在生产实例上（它们的作用只是验证响应与异常链路契约）；
// 生产探活走 /actuator/health。
@Profile("!prod")
public class MetaController {

    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.success("pong");
    }

    @GetMapping("/error/business")
    public Result<Void> business() {
        throw new BusinessException(ErrorCode.NOT_FOUND, "业务探针触发");
    }

    @GetMapping("/error/unknown")
    public Result<Void> unknown() {
        throw new IllegalStateException("未预期异常探针——响应体不应包含本行文本");
    }
}
