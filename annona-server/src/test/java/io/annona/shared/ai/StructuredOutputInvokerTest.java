package io.annona.shared.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.spi.dto.ModelChatMessage;
import io.annona.spi.dto.ModelResponse;
import io.annona.spi.model.ModelProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 结构化输出重试器：成功路径、带反馈重试、围栏剥离、重试耗尽（P1b-02/P1b-06 共用件）。 */
@Tag("slice")
class StructuredOutputInvokerTest {

    private record Payload(String name, int count) {
    }

    @SuppressWarnings("unchecked")
    private StructuredOutputInvoker invokerWith(ModelProvider provider, int maxAttempts) {
        ObjectProvider<ModelProvider> objectProvider = mock(ObjectProvider.class);
        when(objectProvider.getIfAvailable()).thenReturn(provider);
        StructuredOutputProperties properties = new StructuredOutputProperties();
        properties.setMaxAttempts(maxAttempts);
        return new StructuredOutputInvoker(objectProvider, properties,
            new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    @DisplayName("首次即解析成功：只调用一次，返回记录")
    void firstAttemptSucceeds() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.chat(any(), any())).thenReturn(
            new ModelResponse("{\"name\":\"并发\",\"count\":3}", null, "m"));
        Payload payload = invokerWith(provider, 3)
            .invoke("sys", "user", Payload.class);
        assertThat(payload.name()).isEqualTo("并发");
        assertThat(payload.count()).isEqualTo(3);
        verify(provider, times(1)).chat(any(), any());
    }

    @Test
    @DisplayName("markdown 围栏与前后闲话被剥离后解析")
    void extractsJsonFromFencedReply() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.chat(any(), any())).thenReturn(new ModelResponse(
            "好的，以下是结果：\n```json\n{\"name\":\"x\",\"count\":1}\n```\n以上。", null, "m"));
        assertThat(invokerWith(provider, 3).invoke("sys", "user", Payload.class).name())
            .isEqualTo("x");
    }

    @Test
    @DisplayName("第一次坏输出→重试的 user 消息带错误反馈；第二次成功")
    void retriesWithErrorFeedback() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.chat(any(), any())).thenReturn(new ModelResponse("这不是 JSON", null, "m"),
            new ModelResponse("{\"name\":\"y\",\"count\":2}", null, "m"));
        Payload payload = invokerWith(provider, 3).invoke("sys", "user", Payload.class);
        assertThat(payload.count()).isEqualTo(2);
        verify(provider, times(2)).chat(any(), any());
    }

    @Test
    @DisplayName("重试耗尽抛 AI_SERVICE_ERROR，消息含最后一次解析错误")
    void exhaustsAttemptsToBusinessError() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.chat(any(), any())).thenReturn(new ModelResponse("永远不是 JSON", null, "m"));
        assertThatThrownBy(() -> invokerWith(provider, 2).invoke("sys", "user", Payload.class))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("2 次");
        verify(provider, times(2)).chat(any(), any());
    }

    @Test
    @DisplayName("模型未配置：调用即 AI_SERVICE_UNAVAILABLE")
    void missingProviderFails() {
        assertThatThrownBy(() -> invokerWith(null, 3).invoke("sys", "user", Payload.class))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("模型未配置");
    }
}
