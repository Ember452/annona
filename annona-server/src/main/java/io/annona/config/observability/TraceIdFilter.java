package io.annona.config.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个请求建立 {@code traceId} 链路标识（MDC 键 {@code traceId}）。
 *
 * <p>它是三件事的共同来源：
 * <ol>
 *   <li>{@code Result.traceId} —— {@code io.annona.common.result.Result} 从 MDC 读取并随响应返回，
 *       用户截图/反馈里的这一个字段就能定位到服务端日志；</li>
 *   <li>日志输出 —— {@code logback-spring.xml} 的 {@code %X{traceId}} 转换符；</li>
 *   <li>响应头 {@code X-Trace-Id} —— 便于前端与反代（nginx）把同一次调用串起来。</li>
 * </ol>
 *
 * <p><b>入站头的处理</b>：只有形如 {@code [A-Za-z0-9._-]{1,64}} 的 {@code X-Trace-Id} 才会被沿用
 * （跨服务串联场景需要它），否则一律新生成。这不是可有可无的洁癖：直接把任意字符串写进
 * 日志与响应头，等于开放日志注入（换行伪造日志行）与响应头注入。
 *
 * <p>{@code finally} 里必须 {@code MDC.remove}，否则线程复用（Tomcat 线程池）会让上一个请求的
 * traceId 泄漏到下一个请求，日志归因立刻失真。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 对外头名与 MDC 键。 */
    public static final String TRACE_ID = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final Pattern SAFE_INBOUND = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }

    /** 包级可见，供单测直接断言"脏输入不会被沿用"这条安全约束。 */
    static String resolveTraceId(String inbound) {
        if (inbound != null && SAFE_INBOUND.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
