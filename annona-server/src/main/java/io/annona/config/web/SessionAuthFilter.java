package io.annona.config.web;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 对 {@code /api/**} 做身份解析与<b>强制鉴权</b>：
 * <ol>
 *   <li>按 {@code annona.identity.mode} 取凭据（{@code local} → 会话 Cookie；{@code platform} →
 *       受信反代请求头；{@code none} → 无需凭据），交给装配中的身份源校验——凭据读取
 *       委托 {@link RequestCredentials}（P3-01 抽取：WS 握手是第二个消费方）；</li>
 *   <li>命中则把 userId 放入请求属性 {@link #ATTR_USER_ID}，供 {@link CurrentPrincipalArgumentResolver}
 *       组装主体视图；</li>
 *   <li>未命中且路径不在白名单 → 经 {@link HandlerExceptionResolver} 复抛
 *       {@link BusinessException}({@code UNAUTHORIZED})，即 HTTP 200 + {@code Result.error(1004)}，
 *       与全局异常出口、前端拦截器契约保持一致（不在此手写 JSON，避免响应形状漂移）。</li>
 * </ol>
 *
 * <p>只处理 {@code /api/**}：静态资源与 actuator 不做无意义的凭据读取；否则强制鉴权会把 SPA
 * 深链 404 变成 1004。{@link io.annona.config.observability.TraceIdFilter} 是
 * {@code HIGHEST_PRECEDENCE}，早于本过滤器，故拒绝响应仍带 traceId。
 *
 * <p>P1a-01 曾"只解析不强制"；本类在 P1a-02 补齐强制鉴权与模式化凭据（见
 * docs/specs/2026-09-26-identity-provider-modes-adr.md）。
 *
 * <p>{@code @ConditionalOnWebApplication(SERVLET)}：构造器依赖的 {@code handlerExceptionResolver}
 * 只由 servlet 语境的 MVC 自动配置提供，非 web 上下文（如 docker 组 IT 的 NONE 环境）装配本类
 * 必得 NoSuchBeanDefinition。选条件装配而非 {@code ObjectProvider} 延迟解析——过滤器离开
 * servlet 管线毫无意义，缺席比空转诚实；若未来出现无 web 场景需要凭据读取，再拆出独立组件。
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    /** 解析出的 userId 存放的请求属性键。 */
    public static final String ATTR_USER_ID = "annona.principal.userId";

    /** 只对 API 生效；其余路径（静态资源、actuator）直接跳过。 */
    private static final String API_PREFIX = "/api/";

    /** 未登录也必须可达的端点：注册 / 登录 / 登出（登出对未登录者返回成功并清 Cookie）。 */
    private static final List<String> PUBLIC_PATHS =
        List.of("/api/auth/register", "/api/auth/login", "/api/auth/logout");

    /** 公开前缀：元信息接口（版本、能力开关）在未登录时也应可读。 */
    private static final List<String> PUBLIC_PREFIXES = List.of("/api/meta/");

    private final RequestCredentials credentials;
    private final HandlerExceptionResolver exceptionResolver;

    public SessionAuthFilter(RequestCredentials credentials,
                             @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.credentials = credentials;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var principal = credentials.authenticate(request);
        if (principal.isPresent()) {
            request.setAttribute(ATTR_USER_ID, principal.get().id());
        } else if (!isPublicPath(request.getRequestURI())) {
            exceptionResolver.resolveException(request, response, null,
                new BusinessException(ErrorCode.UNAUTHORIZED));
            return;
        }
        chain.doFilter(request, response);
    }

    /** 未登录也必须可达的端点与公开前缀的包级可见判断（测试直接断言白名单边界）。 */
    static boolean isPublicPath(String uri) {
        return PUBLIC_PATHS.contains(uri) || PUBLIC_PREFIXES.stream().anyMatch(uri::startsWith);
    }
}