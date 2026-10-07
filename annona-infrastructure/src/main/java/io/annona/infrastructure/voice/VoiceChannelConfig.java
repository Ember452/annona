package io.annona.infrastructure.voice;

import io.annona.common.voice.StreamingAsrProvider;
import io.annona.common.voice.TtsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 语音通道装配（voice-adr §决策 2）。asr/tts 各自按
 * {@code annona.model.asr.provider} / {@code annona.model.tts.provider} 三态择一：
 * none（默认）不装配——voice 编排层以 Optional 注入，使用点报语音段错误码并走降级
 * 路径（TTS 无 → 字幕面试；ASR 无 → 手动提交文字）；fake 供 CI/本机跑通链路；
 * dashscope 为 Omni realtime WebSocket 直连真实现。
 */
@Configuration
@EnableConfigurationProperties({AsrProperties.class, TtsProperties.class})
public class VoiceChannelConfig {

    @Bean
    @ConditionalOnProperty(prefix = "annona.model.asr", name = "provider", havingValue = "dashscope")
    public StreamingAsrProvider dashScopeAsrProvider(AsrProperties properties) {
        return new DashScopeAsrProvider(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "annona.model.asr", name = "provider", havingValue = "fake")
    public StreamingAsrProvider fakeAsrProvider() {
        return new FakeAsrProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "annona.model.tts", name = "provider", havingValue = "dashscope")
    public TtsProvider dashScopeTtsProvider(TtsProperties properties) {
        return new DashScopeTtsProvider(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "annona.model.tts", name = "provider", havingValue = "fake")
    public TtsProvider fakeTtsProvider() {
        return new FakeTtsProvider();
    }
}
