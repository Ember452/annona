package io.annona.infrastructure.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * chat 通道装配（qa-streaming-adr）。{@code provider=openai-compatible} 时装配 OpenAI
 * 兼容实现（同一 bean 同时是 spi 的 ModelProvider 与 common 的 StreamingChatProvider）；
 * {@code provider=fake} 时装配确定性流式 fake；none（默认）两者都不装配——qa 服务以
 * Optional 注入，缺 bean 时提问报 QA_MODEL_NOT_CONFIGURED（启动不拦，错误后移到使用点）。
 */
@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {

    @Bean
    @ConditionalOnProperty(prefix = "annona.model.chat", name = "provider", havingValue = "openai-compatible")
    public OpenAiCompatibleChatProvider openAiCompatibleChatProvider(ChatProperties properties) {
        return new OpenAiCompatibleChatProvider(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "annona.model.chat", name = "provider", havingValue = "fake")
    public FakeStreamingChatProvider fakeStreamingChatProvider() {
        return new FakeStreamingChatProvider();
    }
}
