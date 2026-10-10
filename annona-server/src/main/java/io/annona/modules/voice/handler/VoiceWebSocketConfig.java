package io.annona.modules.voice.handler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * /ws/voice 注册（voice-adr §决策 5）：握手鉴权走 {@link VoiceHandshakeInterceptor}
 * （身份凭据与 /api/** 同源），通道消息体由 {@link VoiceWebSocketHandler} 承接。
 * 跨域放开到 *：鉴权在握手期完成（Cookie 凭据），升级请求不携带可被 CSRF 利用的
 * 状态变更——生产经 nginx 同源反代，此处放开只影响本机直连调试。
 *
 * <p>{@code @ConditionalOnWebApplication(SERVLET)}：{@code @EnableWebSocket} 的支撑设施
 * 依赖 servlet 语境（SessionAuthFilter 同款教训——NONE 上下文 docker 组 IT 连坐失败）。
 * {@code annona.voice.enabled}：test profile（无 DB 冒烟，懒加载）里
 * DelegatingWebSocketConfiguration 会强制实例化全部 WebSocketConfigurer，把
 * voiceSessionService→JPA repository 的懒加载链整条拉醒——无 DB 冒烟上下文必须关
 * （questionbank/evaluation 门控同先例）。因此 {@code VoiceProperties} 的注册点不得放在本类：
 * 消费它的 voice service / handler 不受这道 web 门控，NONE 上下文会因缺 bean 整片连坐
 * （CI run #115），注册已转到不受门控的 {@code VoiceInterviewService}。
 */
@Configuration
@EnableWebSocket
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "annona.voice", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class VoiceWebSocketConfig implements WebSocketConfigurer {

    public static final String ENDPOINT = "/ws/voice";

    private final VoiceWebSocketHandler voiceWebSocketHandler;
    private final VoiceHandshakeInterceptor handshakeInterceptor;

    public VoiceWebSocketConfig(VoiceWebSocketHandler voiceWebSocketHandler,
                                VoiceHandshakeInterceptor handshakeInterceptor) {
        this.voiceWebSocketHandler = voiceWebSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceWebSocketHandler, ENDPOINT)
            .addInterceptors(handshakeInterceptor)
            .setAllowedOriginPatterns("*");
    }
}
