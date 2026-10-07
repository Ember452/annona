package io.annona.modules.voice.handler;

import io.annona.config.web.RequestCredentials;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * /ws/voice 握手鉴权（voice-adr §决策 5）：复用 {@link RequestCredentials} 做身份解析
 * （local Cookie / platform 反代头 / none 三模式同 SessionAuthFilter 口径），未命中直接
 * 401 拒绝升级——WS 不走 SessionAuthFilter（它只拦 /api/**），必须在握手期把好同一道门。
 * 命中后把 userId 放入握手属性，供 handler 建立连接级状态。
 */
@Component
public class VoiceHandshakeInterceptor implements HandshakeInterceptor {

    /** 握手属性键：解析出的 userId（handler 内读取）。 */
    public static final String ATTR_USER_ID = "voice.principal.userId";

    private final RequestCredentials credentials;

    public VoiceHandshakeInterceptor(RequestCredentials credentials) {
        this.credentials = credentials;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }
        var principal = credentials.authenticate(servletRequest.getServletRequest());
        if (principal.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_USER_ID, principal.get().id());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需清理：握手属性随 WS 会话生命周期走
    }
}
