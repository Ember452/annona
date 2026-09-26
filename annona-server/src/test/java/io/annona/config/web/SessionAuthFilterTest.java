package io.annona.config.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.identity.service.IdentityProperties;
import io.annona.modules.identity.service.SessionProperties;
import io.annona.spi.dto.Principal;
import io.annona.spi.identity.IdentityProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * {@link SessionAuthFilter} 的 Mockito 切片：模式化凭据读取、强制鉴权、白名单与过滤范围。
 * 不启动 Web 服务器（本机可跑，{@code @Tag("slice")}）。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionAuthFilter：模式化凭据 + /api/** 强制鉴权")
class SessionAuthFilterTest {

    private static final String USER_ID = "33333333-3333-3333-3333-333333333333";

    @Mock
    private IdentityProvider identityProvider;
    @Mock
    private HandlerExceptionResolver exceptionResolver;
    @Mock
    private FilterChain chain;

    private final IdentityProperties identityProperties = new IdentityProperties();
    private final SessionProperties sessionProperties = new SessionProperties();

    private SessionAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SessionAuthFilter(identityProvider, identityProperties, sessionProperties, exceptionResolver);
    }

    private static MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    private void authenticateAs(String userId) {
        when(identityProvider.authenticate(any())).thenReturn(Optional.of(new Principal(userId, null, Set.of())));
    }

    @Nested
    @DisplayName("凭据读取与强制鉴权")
    class Authentication {

        @Test
        @DisplayName("local：有效会话 Cookie → 写入请求属性并继续过滤链")
        void localWithCookieAuthenticates() throws Exception {
            identityProperties.setMode(IdentityProperties.Mode.LOCAL);
            authenticateAs(USER_ID);
            MockHttpServletRequest request = request("/api/me");
            request.setCookies(new Cookie(sessionProperties.getCookie(), "tok"));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, chain);

            assertThat(request.getAttribute(SessionAuthFilter.ATTR_USER_ID)).isEqualTo(USER_ID);
            verify(identityProvider).authenticate("tok");
            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("local：无 Cookie 访问受保护端点 → 经全局出口拒绝且不继续过滤链")
        void localWithoutCookieIsRejected() throws Exception {
            identityProperties.setMode(IdentityProperties.Mode.LOCAL);
            when(identityProvider.authenticate(any())).thenReturn(Optional.empty());
            MockHttpServletRequest request = request("/api/study/rooms");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, chain);

            ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
            verify(exceptionResolver).resolveException(eq(request), eq(response), isNull(), captor.capture());
            assertThat(captor.getValue()).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("local：无 Cookie 访问白名单端点（登录）→ 放行，不写主体属性")
        void localWithoutCookiePassesPublicEndpoint() throws Exception {
            identityProperties.setMode(IdentityProperties.Mode.LOCAL);
            when(identityProvider.authenticate(any())).thenReturn(Optional.empty());
            MockHttpServletRequest request = request("/api/auth/login");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, chain);

            assertThat(request.getAttribute(SessionAuthFilter.ATTR_USER_ID)).isNull();
            verifyNoInteractions(exceptionResolver);
            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("platform：受信反代头 → 认证通过")
        void platformReadsTrustedHeader() throws Exception {
            identityProperties.setMode(IdentityProperties.Mode.PLATFORM);
            authenticateAs(USER_ID);
            MockHttpServletRequest request = request("/api/me");
            request.addHeader(identityProperties.getPlatformHeader(), "someone@example.test");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, chain);

            assertThat(request.getAttribute(SessionAuthFilter.ATTR_USER_ID)).isEqualTo(USER_ID);
            verify(identityProvider).authenticate("someone@example.test");
        }

        @Test
        @DisplayName("none：无需任何凭据即认证通过")
        void noneModeAuthenticatesWithoutCredential() throws Exception {
            identityProperties.setMode(IdentityProperties.Mode.NONE);
            authenticateAs(USER_ID);
            MockHttpServletRequest request = request("/api/me");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, chain);

            assertThat(request.getAttribute(SessionAuthFilter.ATTR_USER_ID)).isEqualTo(USER_ID);
            verify(identityProvider).authenticate(null);
            verify(chain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("过滤范围与白名单")
    class Scope {

        @Test
        @DisplayName("只处理 /api/**：静态资源与根路径跳过")
        void skipsNonApiPaths() {
            assertThat(filter.shouldNotFilter(request("/"))).isTrue();
            assertThat(filter.shouldNotFilter(request("/assets/index.js"))).isTrue();
            assertThat(filter.shouldNotFilter(request("/actuator/health"))).isTrue();
            assertThat(filter.shouldNotFilter(request("/api/me"))).isFalse();
        }

        @Test
        @DisplayName("白名单：注册/登录/登出与 /api/meta/ 前缀放行，其余 /api 需鉴权")
        void whitelistBoundaries() {
            assertThat(SessionAuthFilter.isPublicPath("/api/auth/register")).isTrue();
            assertThat(SessionAuthFilter.isPublicPath("/api/auth/login")).isTrue();
            assertThat(SessionAuthFilter.isPublicPath("/api/auth/logout")).isTrue();
            assertThat(SessionAuthFilter.isPublicPath("/api/meta/version")).isTrue();

            assertThat(SessionAuthFilter.isPublicPath("/api/auth/password")).isFalse();
            assertThat(SessionAuthFilter.isPublicPath("/api/me")).isFalse();
            assertThat(SessionAuthFilter.isPublicPath("/api/study/rooms")).isFalse();
        }
    }
}