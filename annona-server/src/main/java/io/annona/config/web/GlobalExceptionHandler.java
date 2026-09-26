package io.annona.config.web;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常出口：本类是 annona 对外接口的<b>唯一</b>异常转换点，业务代码禁止在
 * Controller 里写 try/catch 返 {@link Result}。
 *
 * <p>放在 {@code config/web}（全局装配层）而不是 {@code annona-common}，是为了让
 * common 保持零框架依赖：common 只留 {@code Result}/{@code ErrorCode}/{@code BusinessException}
 * 三个纯 Java 类型，可被任何层复用（含对外发布的 annona-spi）。
 *
 * <p><b>状态码策略（区分两类失败，这一点刻意不同于"一律返回 200"）</b>：
 * <ul>
 *   <li><b>业务失败</b>（{@link BusinessException}）→ HTTP 200 + {@code Result.error(code, message)}。
 *       请求确实被正确处理并给出了业务结论，前端靠 {@code code} 分流，与
 *       {@code annona-web/src/api/request.ts} 的拦截器约定一致。</li>
 *   <li><b>传输与路由层失败</b>（404 / 405 / 请求体不可读 / 未预期异常）→ <b>真实 HTTP 状态码</b>
 *       + 同样的 {@code Result} 响应体。若把它们也压成 200，会造成三个具体后果：
 *       ① 前端 axios 的 error 分支永不触发，无法区分"接口挂了"与"业务拒绝"；
 *       ② 反向代理与监控看不到 4xx/5xx，故障被静默吞掉；
 *       ③ SPA 未做 fallback 时（阶段总结 D1），deep-link 的 404 会以 200 JSON 返回，
 *       把问题伪装成"前端拿到了数据但渲染不出来"，定位成本数倍。</li>
 * </ul>
 *
 * <p>响应体结构对两类失败保持一致（都是 {@code Result}），所以前端既能按状态码分流，
 * 也能按 {@code code} 显示文案。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务可预期失败：透传 code + 面向用户的 message，HTTP 200。 */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /** 未匹配到任何路由或静态资源：404。不打 ERROR 日志（扫描器与错链接会制造噪音）。 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(NoResourceFoundException e) {
        log.debug("未找到资源: {}", e.getResourcePath());
        return Result.error(ErrorCode.NOT_FOUND);
    }

    /** 方法不匹配：405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMessage());
        return Result.error(ErrorCode.METHOD_NOT_ALLOWED);
    }

    /** 请求体不可读 / 反序列化失败：400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(ErrorCode.BAD_REQUEST);
    }

    /**
     * 数据库完整性冲突（唯一约束 / 外键等，典型：先查后插竞态、重复提交）：409 + 固定文案。
     * 精确的业务文案（如 direction 2101）由 Service 预检查给出，本兑底只承接并发窗口内
     * 穿过预检查的漏网请求（事务内 catch 不可行：Hibernate 异常后 session 必须回滚），
     * 避免落进 {@link #handleUnknown} 变成 500。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("数据完整性冲突: {}", e.getMessage());
        return Result.error(ErrorCode.DATA_CONFLICT);
    }

    /**
     * 未知异常兑底：HTTP 500 + 固定文案，完整栈只进日志不外泄。
     * 异常作为最后一个参数传给 SLF4J 以保留堆栈。
     */
    @ExceptionHandler(Throwable.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnknown(Throwable t) {
        log.error("未预期异常", t);
        return Result.error(ErrorCode.INTERNAL_ERROR, "系统繁忙，请稍后重试");
    }
}
