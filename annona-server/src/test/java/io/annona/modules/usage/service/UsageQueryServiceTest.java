package io.annona.modules.usage.service;

import static org.mockito.Mockito.when;

import io.annona.modules.usage.config.UsageProperties;
import io.annona.modules.usage.dto.SessionUsageView;
import io.annona.modules.usage.repository.TokenUsageRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 成本钻取计价口径：有价则算、缺价如实 null、价目表未配置置 priced=false。 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
class UsageQueryServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private TokenUsageRepository repository;
    @Mock
    private UsageProperties properties;

    @Test
    @DisplayName("价目表命中：成本 = (输入+输出)/1000 × 单价，priced=true")
    void pricesKnownModel() {
        when(properties.getModelPrices()).thenReturn(Map.of("m1", 0.5));
        when(repository.aggregateBySession(SESSION, USER)).thenReturn(
            List.<Object[]>of(new Object[]{"m1", 300L, 200L, 2L}));

        SessionUsageView view = new UsageQueryService(repository, properties)
            .sessionUsage(USER, SESSION);

        Assertions.assertThat(view.priced()).isTrue();
        Assertions.assertThat(view.items()).hasSize(1);
        Assertions.assertThat(view.items().get(0).estimatedCost()).isEqualTo(0.25);
    }

    @Test
    @DisplayName("价目表缺该模型：estimatedCost=null（宁可不显示，不给假数）")
    void unpricedModelGetsNullCost() {
        when(properties.getModelPrices()).thenReturn(Map.of("m2", 0.5));
        when(repository.aggregateBySession(SESSION, USER)).thenReturn(
            List.<Object[]>of(new Object[]{"m1", 300L, 200L, 2L}));

        SessionUsageView view = new UsageQueryService(repository, properties)
            .sessionUsage(USER, SESSION);

        Assertions.assertThat(view.priced()).isTrue();
        Assertions.assertThat(view.items().get(0).estimatedCost()).isNull();
    }

    @Test
    @DisplayName("价目表整体未配置：priced=false，前端显示占位文案")
    void emptyPriceTableMarksUnpriced() {
        when(properties.getModelPrices()).thenReturn(Map.of());
        when(repository.aggregateBySession(SESSION, USER)).thenReturn(List.of());

        SessionUsageView view = new UsageQueryService(repository, properties)
            .sessionUsage(USER, SESSION);

        Assertions.assertThat(view.priced()).isFalse();
        Assertions.assertThat(view.items()).isEmpty();
    }
}
