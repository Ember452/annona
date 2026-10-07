package io.annona.config.web;

import io.annona.modules.identity.service.IdentityProperties;
import io.annona.modules.identity.service.SessionProperties;
import io.annona.spi.dto.Principal;
import io.annona.spi.identity.IdentityProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 凭据读取与身份解析的唯一出处（P3-01 从 SessionAuthFilter 抽取：WS 握手是第二个
 * 消费方，第三份 Cookie 遍历拷贝会坐实 TD-10）。语义与原私有方法完全一致——
 * 改动任何一个消费方行为前先对照 identity-provider-modes-adr。
 *
 * <p>按 {@code annona.identity.mode} 取凭据：local 读会话 Cookie，platform 读受信反代头
 * （拒绝重复身份头 + 常数时间共享密钥校验），none 不看凭据。
 */
@Component
public class RequestCredentials {

    private final IdentityProvider identityProvider;
    private final IdentityProperties identityProperties;
    private final SessionProperties sessionProperties;

    public RequestCredentials(IdentityProvider identityProvider,
                              IdentityProperties identityProperties,
                              SessionProperties sessionProperties) {
        this.identityProvider = identityProvider;
        this.identityProperties = identityProperties;
        this.sessionProperties = sessionProperties;
    }

    /** 读凭据并解析主体；未命中返回 empty，不抛异常。 */
    public Optional<Principal> authenticate(HttpServletRequest request) {
        return identityProvider.authenticate(readCredential(request));
    }

    /** 按模式取凭据：local 读会话 Cookie，platform 读受信反代头，none 不看凭据。 */
    private String readCredential(HttpServletRequest request) {
        return switch (identityProperties.getMode()) {
            case LOCAL -> readCookie(request);
            case PLATFORM -> readPlatformCredential(request);
            case NONE -> null;
        };
    }

    /**
     * platform 凭据读取，叠加两道代码强制（L3 评审 CWE-290：不把信任只写在文档里）：
     * <ol>
     *   <li><b>拒绝重复身份头</b>：{@code X-Auth-Request-Email} 出现 0 或 ≥2 个值都视为不可信
     *       （头走私 / 反代未剥离），凭据按空；</li>
     *   <li><b>共享密钥证明（配了才强制）</b>：{@code platformSecret} 非空时，请求必须携带常数时间
     *       匹配的密钥头，否则凭据按空——使“实例被直连”也无法伪造身份。</li>
     * </ol>
     */
    private String readPlatformCredential(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(identityProperties.getPlatformHeader()));
        if (values.size() != 1) {
            return null;
        }
        String secret = identityProperties.getPlatformSecret();
        if (secret != null && !secret.isBlank()) {
            String provided = request.getHeader(identityProperties.getPlatformSecretHeader());
            if (provided == null || !MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
        }
        return values.get(0);
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
