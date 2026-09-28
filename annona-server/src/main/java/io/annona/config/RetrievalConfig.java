package io.annona.config;

import io.annona.config.properties.RetrievalProperties;
import io.annona.spi.fake.FakeRetriever;
import io.annona.spi.retrieval.Retriever;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 检索后端装配（P1a-07）。业务侧只注入 {@link Retriever} 接口，具体后端由
 * {@code annona.retrieval.backend} 选择——pgvector 实现自带组件扫描（它在 modules 里），
 * 本类只负责未发布前唯一需要的另一档：{@code fake}。
 *
 * <p>没有 {@code es} 档：SPI 保留实现位，但默认不部署 ES（storage-single-postgres-adr），
 * 提前开一个装了会启动失败的取值是预留空位。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetrievalProperties.class)
public class RetrievalConfig {

    /**
     * {@code backend=fake} 时装配内存空命中实现。
     *
     * <p>这一档的存在理由是 P1a-07 的验收句"改 {@code annona.retrieval.backend} 不报错"：
     * 换后端要能真的换得动，而不只是配置项能读进来。
     */
    @Bean
    @ConditionalOnProperty(prefix = "annona.retrieval", name = "backend", havingValue = RetrievalProperties.BACKEND_FAKE)
    public Retriever fakeRetriever() {
        return new FakeRetriever();
    }
}
