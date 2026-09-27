package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.knowledge.controller.KnowledgeDocController;
import io.annona.modules.knowledge.listener.KnowledgeRecoveryScheduler;
import io.annona.modules.knowledge.listener.KnowledgeVectorizeStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 门控守卫的历史故障形态（P1a-05 CI 实测）：恢复调度<b>开启</b>而入库通道<b>关闭</b>——
 * 恰好是"在场 bean 与缺席 bean 互相引用"的混合装配，KnowledgeVectorizeStream 最初
 * 就死在这个组合上（27 个完整上下文 IT 连坐）。本 IT 失败 = 有人让这两个门控 bean
 * 之间（或它们与常驻链之间）重新出现了硬注入。
 *
 * <p>本机不跑；CI 的 docker-it job（services: pgvector + redis）执行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "annona.storage.enabled=false",
    "annona.knowledge.ingest.enabled=false",
    "annona.knowledge.recovery.enabled=true",
    "annona.model.embedding.provider=none",
})
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("门控守卫：恢复调度开启而入库通道关闭，混合装配必须可启动")
class RecoveryWithoutIngestContextIT {

    @Autowired
    private ObjectProvider<KnowledgeVectorizeStream> streamProvider;
    @Autowired
    private KnowledgeRecoveryScheduler recoveryScheduler;
    @Autowired
    private KnowledgeDocController knowledgeDocController;

    @Test
    @DisplayName("调度器在场、消费流缺席，controller 链照常装配")
    void recoveryOnIngestOffContextBoots() {
        assertThat(streamProvider.getIfAvailable()).isNull();
        assertThat(recoveryScheduler).isNotNull();
        assertThat(knowledgeDocController).isNotNull();
    }
}
