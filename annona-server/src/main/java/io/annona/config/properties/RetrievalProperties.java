package io.annona.config.properties;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索通道配置（{@code annona.retrieval.*}）。
 *
 * <p>{@code backend} 决定装配<b>哪一整套</b>检索机器（不是某个参数）：pgvector 后端里
 * 语义通道、关键词通道、trgm 兜底与 RRF 融合是一体的，换 ES 时 BM25 + KNN + 融合由
 * 那个实现整体接管（retrieval-hybrid-adr §决策 3）。
 */
@ConfigurationProperties(prefix = "annona.retrieval")
public class RetrievalProperties {

    public static final String BACKEND_PGVECTOR = "pgvector";
    public static final String BACKEND_FAKE = "fake";

    private static final List<String> VALID_BACKENDS = List.of(BACKEND_PGVECTOR, BACKEND_FAKE);

    private String backend = BACKEND_PGVECTOR;

    /**
     * 未知值启动即拒（fail-fast），而不是静默回落到默认后端。
     *
     * <p>选 fail-fast：拼错一个值就"以为在用 pgvector、实际跑的是 fake"，而检索指标会
     * 一起失真——这与 P1a-05 那条"错误后移到使用点"的取舍相反，因为这里错一个字母的代价
     * 是<b>整批数字不可信</b>，而使用点报错只能影响单次请求。
     */
    @PostConstruct
    void validateBackend() {
        if (!VALID_BACKENDS.contains(backend)) {
            throw new IllegalStateException("annona.retrieval.backend='" + backend
                + "' 不受支持。可选值：" + VALID_BACKENDS
                + "（fake = 内存空命中，用于在不接 PG 的环境里跑上层业务）");
        }
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }
}
