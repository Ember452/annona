package io.annona.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.common.model.StreamingChatProvider;
import io.annona.spi.model.ModelProvider;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * chat 装配路由（qa-streaming-adr §决策 5）：provider 三态的 bean 存在性。
 * 纯 spring-context 测试（无 web / 无数据库），本机默认跑。
 */
@DisplayName("ChatConfig 装配路由")
class ChatRoutingTest {

    @Test
    @DisplayName("provider=none（默认）：两个 chat 端口都不装配，使用点以 Optional 兜底")
    void noneWiresNothing() {
        try (AnnotationConfigApplicationContext context = context("annona.model.chat.provider=none")) {
            assertThat(context.getBeanProvider(StreamingChatProvider.class).getIfAvailable()).isNull();
            assertThat(context.getBeanProvider(ModelProvider.class).getIfAvailable()).isNull();
        }
    }

    @Test
    @DisplayName("provider=fake：装配流式 fake，不装配同步 ModelProvider")
    void fakeWiresStreamingOnly() {
        try (AnnotationConfigApplicationContext context = context("annona.model.chat.provider=fake")) {
            assertThat(context.getBean(StreamingChatProvider.class))
                .isInstanceOf(FakeStreamingChatProvider.class);
            assertThat(context.getBeanProvider(ModelProvider.class).getIfAvailable()).isNull();
        }
    }

    @Test
    @DisplayName("provider=openai-compatible：同一 bean 同时是同步与流式两个端口的实现")
    void openAiCompatibleWiresBothPorts() {
        try (AnnotationConfigApplicationContext context =
                 context("annona.model.chat.provider=openai-compatible")) {
            Object streaming = context.getBean(StreamingChatProvider.class);
            Object sync = context.getBean(ModelProvider.class);
            assertThat(streaming).isInstanceOf(OpenAiCompatibleChatProvider.class);
            assertThat(sync).isSameAs(streaming);
        }
    }

    private AnnotationConfigApplicationContext context(String property) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources()
            .addFirst(new MapPropertySource("test", Map.of(property.split("=")[0], property.split("=")[1])));
        context.register(ChatConfig.class);
        context.refresh();
        return context;
    }
}
