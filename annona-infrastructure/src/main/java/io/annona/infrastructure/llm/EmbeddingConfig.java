package io.annona.infrastructure.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * embedding 通道装配。仅当 {@code annona.model.embedding.provider=openai-compatible}
 * 时装配实现；none（默认）/fake（测试）走各自路径——knowledge 服务以 Optional 注入，
 * 缺 bean 时向量化报 KB_EMBEDDING_NOT_CONFIGURED（启动不拦，ADR §决策 9）。
 */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfig {

    @Bean
    @ConditionalOnProperty(prefix = "annona.model.embedding", name = "provider", havingValue = "openai-compatible")
    public OpenAiCompatibleEmbeddingProvider openAiCompatibleEmbeddingProvider(EmbeddingProperties properties) {
        return new OpenAiCompatibleEmbeddingProvider(properties);
    }
}
