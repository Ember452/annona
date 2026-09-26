package io.annona.config.web;

import io.annona.modules.identity.service.SessionProperties;
import io.annona.modules.identity.service.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 读取会话 Cookie（{@code annona.session.cookie}）→ 解析 userId → 放入请求属性，
 * 供 {@link CurrentPrincipalArgumentResolver} 使用。命中即滑动续期（经 SessionService）。
 *
 * <p>P1a-01 只做<b>解析</b>不做<b>强制</b>：除 /api/me 外的接口本批不设门禁（无鉴权需求）；
 * 强制鉴权与 IdentityProvider 模式化在 P1a-02 引入。
 */
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    /** 解析出的 userId 存放的请求属性键。 */
    public static final String ATTR_USER_ID = "annona.principal.userId";

    private final SessionService sessionService;
    private final SessionProperties sessionProperties;

    public SessionAuthFilter(SessionService sessionService, SessionProperties sessionProperties) {
        this.sessionService = sessionService;
        this.sessionProperties = sessionProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String token = readCookie(request);
        if (token != null) {
            Optional<String> userId = sessionService.resolveAndSlide(token);
            userId.ifPresent(id -> request.setAttribute(ATTR_USER_ID, id));
        }
        chain.doFilter(request, response);
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = sessionProperties.getCookie();
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
