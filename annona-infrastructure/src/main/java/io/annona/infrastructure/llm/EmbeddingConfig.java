package io.annona.infrastructure.llm;

import io.annona.spi.fake.FakeEmbeddingProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * embedding 通道装配。{@code provider=openai-compatible} 时装配 OpenAI 兼容实现；
 * {@code provider=fake} 时装配确定性向量实现（供无 Key 环境跑通入库与评测管道）；
 * none（默认）两者都不装配——knowledge 服务以 Optional 注入，缺 bean 时向量化报
 * KB_EMBEDDING_NOT_CONFIGURED（启动不拦，错误后移到使用点，ADR §决策 9）。
 */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfig {

    @Bean
    @ConditionalOnProperty(prefix = "annona.model.embedding", name = "provider", havingValue = "openai-compatible")
    public OpenAiCompatibleEmbeddingProvider openAiCompatibleEmbeddingProvider(EmbeddingProperties properties) {
        return new OpenAiCompatibleEmbeddingProvider(properties);
    }

    /**
     * 确定性 fake 实现（1024 维，与 V4 向量列对齐）。
     *
     * <p><b>选它换取什么</b>：{@code scripts/rag-eval} 在无 {@code EVAL_EMBEDDING_API_KEY}
     * 的环境（CI 默认）也能跑完整链路，把管道回归与"指标是否达标"分开（retrieval-hybrid-adr §决策 9）。
     * <p><b>它的数字不可读作质量</b>：fake 向量由文本哈希驱动，与语义无关，语义通道召回
     * 本质是随机。因此 {@code retrieval_eval_run.embedding_provider} 必须记录本轮来源，
     * 否则"管道跑通"会被误读成"召回很差"。
     */
    @Bean
    @ConditionalOnProperty(prefix = "annona.model.embedding", name = "provider", havingValue = "fake")
    public FakeEmbeddingProvider fakeEmbeddingProvider() {
        return new FakeEmbeddingProvider();
    }
}
