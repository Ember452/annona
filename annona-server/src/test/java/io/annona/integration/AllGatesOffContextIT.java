package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.common.storage.ObjectStorage;
import io.annona.modules.evaluation.listener.EvaluationRecoveryScheduler;
import io.annona.modules.evaluation.listener.EvaluationStream;
import io.annona.modules.knowledge.controller.KnowledgeDocController;
import io.annona.modules.knowledge.ingest.KnowledgeUploadService;
import io.annona.modules.knowledge.listener.KnowledgeRecoveryScheduler;
import io.annona.modules.knowledge.listener.KnowledgeVectorizeStream;
import io.annona.modules.knowledge.ops.KnowledgeDocLifecycleService;
import io.annona.spi.model.EmbeddingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 门控 bean × 硬注入连坐守卫（P1a-05 CI 实测 27 errors 后立）：所有条件装配<b>显式全关</b>，
 * 完整上下文（含 servlet 管线）必须照常启动——常驻 bean 链（controller → upload/lifecycle）
 * 对门控 bean 一律走 Optional 注入（knowledge-ingestion-adr §决策 9）。
 *
 * <p>本 IT 失败的含义非常具体：<b>有人新增或改动了条件装配的硬注入</b>——去查最近改动的
 * {@code @ConditionalOnProperty}，而不是放松这里的开关值。开关全部写显式值而非依赖
 * profile 默认，防止默认值变动绕开守卫。本机不跑；CI 的 docker-it job（services:
 * pgvector + redis）执行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "annona.storage.enabled=false",
    "annona.knowledge.ingest.enabled=false",
    "annona.knowledge.recovery.enabled=false",
    "annona.questionbank.generate.enabled=false",
    "annona.questionbank.recovery.enabled=false",
    "annona.evaluation.enabled=false",
    "annona.evaluation.recovery.enabled=false",
    "annona.model.embedding.provider=none",
})
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("门控守卫：全部条件装配显式关闭，完整上下文必须可启动")
class AllGatesOffContextIT {

    @Autowired
    private ObjectProvider<ObjectStorage> storageProvider;
    @Autowired
    private ObjectProvider<KnowledgeVectorizeStream> streamProvider;
    @Autowired
    private ObjectProvider<KnowledgeRecoveryScheduler> recoveryProvider;
    @Autowired
    private ObjectProvider<EmbeddingProvider> embeddingProvider;
    @Autowired
    private ObjectProvider<EvaluationStream> evaluationStreamProvider;
    @Autowired
    private ObjectProvider<EvaluationRecoveryScheduler> evaluationRecoveryProvider;
    @Autowired
    private KnowledgeDocController knowledgeDocController;
    @Autowired
    private KnowledgeUploadService uploadService;
    @Autowired
    private KnowledgeDocLifecycleService lifecycleService;

    @Test
    @DisplayName("四个门控 bean 全部缺席，常驻链（controller/upload/lifecycle）照常装配")
    void allOffContextBootsWithGatedBeansAbsent() {
        assertThat(storageProvider.getIfAvailable()).isNull();
        assertThat(streamProvider.getIfAvailable()).isNull();
        assertThat(recoveryProvider.getIfAvailable()).isNull();
        assertThat(embeddingProvider.getIfAvailable()).isNull();
        // 评估链门控 bean 全关时（交卷事件无人监听也无妨，EvaluationService 常驻但不起流）
        assertThat(evaluationStreamProvider.getIfAvailable()).isNull();
        assertThat(evaluationRecoveryProvider.getIfAvailable()).isNull();

        // 常驻链存在性即装配证明：它们构造成功 = 每个门控依赖都走的是 Optional
        assertThat(knowledgeDocController).isNotNull();
        assertThat(uploadService).isNotNull();
        assertThat(lifecycleService).isNotNull();
    }
}
