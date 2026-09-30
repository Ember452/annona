package io.annona.modules.usage.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.quota.DailyQuotaCounter;
import io.annona.common.usage.UsageContext;
import io.annona.common.usage.UsageLedger;
import io.annona.modules.usage.config.UsageProperties;
import io.annona.modules.usage.service.UsageRecorder;
import io.annona.spi.dto.ModelChatMessage;
import io.annona.spi.dto.ModelOptions;
import io.annona.spi.dto.ModelResponse;
import io.annona.spi.dto.UsageInfo;
import io.annona.spi.model.ModelProvider;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/** 装饰器的三步契约：前置熔断、调用透传、按 UsageContext 归属记账（llmprovider-metering-adr）。 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeteredModelProviderTest {

    @Mock
    private ObjectProvider<ModelProvider> candidates;
    @Mock
    private ModelProvider delegate;
    @Mock
    private UsageRecorder recorder;
    @Mock
    private DailyQuotaCounter quota;

    private final UsageProperties properties = new UsageProperties();

    private MeteredModelProvider provider;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    @BeforeEach
    void setUp() {
        properties.setDailyTokenLimit(1000);
        lenient().when(candidates.stream()).thenAnswer(inv -> Stream.of(provider, delegate));
        lenient().when(delegate.name()).thenReturn("glm-4.7");
        lenient().when(delegate.channel()).thenReturn("openai-compatible");
        lenient().when(delegate.chat(any(), any()))
            .thenReturn(new ModelResponse("ok", new UsageInfo(120, 30), "glm-4.7"));
        provider = new MeteredModelProvider(candidates, recorder, quota, properties);
    }

    private List<ModelChatMessage> messages() {
        return List.of(new ModelChatMessage("user", "出题吧"));
    }

    @Test
    @DisplayName("限额内：透传响应，按上下文记一笔账（含 promptHash）")
    void chatPassesThroughAndRecords() {
        try (UsageContext.Scope scope = UsageContext.bind(USER_ID, "QUESTION_GEN", null, null)) {
            ModelResponse response = provider.chat(messages(), ModelOptions.defaults());
            assertThat(response.content()).isEqualTo("ok");
        }
        var captor = ArgumentCaptor.forClass(UsageLedger.UsageEntry.class);
        verify(recorder).record(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.scene()).isEqualTo("QUESTION_GEN");
        // provider = 通道、model = 响应模型 id：两列语义独立（TD-03 的回归钉）
        assertThat(entry.provider()).isEqualTo("openai-compatible");
        assertThat(entry.model()).isEqualTo("glm-4.7");
        assertThat(entry.promptTokens()).isEqualTo(120);
        assertThat(entry.completionTokens()).isEqualTo(30);
        assertThat(entry.promptHash()).hasSize(64);
        verify(quota).tryConsume(anyString(), eq(150L), eq(1000L), any());
    }

    @Test
    @DisplayName("熔断在调用前：当日累计已达上限 → 2800 且不触达底层（省钱才是熔断）")
    void circuitBreaksBeforeCall() {
        when(quota.current(anyString())).thenReturn(1000L);
        try (UsageContext.Scope scope = UsageContext.bind(USER_ID, "QUESTION_GEN", null, null)) {
            assertThatThrownBy(() -> provider.chat(messages(), ModelOptions.defaults()))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED.getCode()));
        }
        verify(delegate, never()).chat(any(), any());
        verify(recorder, never()).record(any());
    }

    @Test
    @DisplayName("provider 未返回 usage：记 0 而不是跳过（账目完整性优先于精度）")
    void nullUsageRecordsZero() {
        when(delegate.chat(any(), any())).thenReturn(new ModelResponse("ok", null, "m"));
        try (UsageContext.Scope scope = UsageContext.bind(USER_ID, "QUESTION_GEN", null, null)) {
            provider.chat(messages(), ModelOptions.defaults());
        }
        var captor = ArgumentCaptor.forClass(UsageLedger.UsageEntry.class);
        verify(recorder).record(captor.capture());
        assertThat(captor.getValue().promptTokens()).isZero();
        verify(quota, never()).tryConsume(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("无上下文调用：不记账不计数（归属不明的账是噪声）")
    void withoutAttributionSkipsRecording() {
        provider.chat(messages(), ModelOptions.defaults());
        verify(recorder, never()).record(any());
    }

    @Test
    @DisplayName("全部原始 provider 缺席（模型未配置）：调用报 1100，装配不受影响")
    void missingDelegateFailsAtCall() {
        when(candidates.stream()).thenAnswer(inv -> Stream.of(provider));
        assertThatThrownBy(() -> provider.chat(messages(), ModelOptions.defaults()))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode())
                    .isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode()));
    }
}
