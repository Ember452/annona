package io.annona.modules.knowledge.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.knowledge.entity.KbDocEntity;
import io.annona.modules.knowledge.listener.KnowledgeVectorizeStream;
import io.annona.modules.knowledge.repository.KbDocRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * KnowledgeDocLifecycleService 的 Mockito 切片：锁死"入库通道可选注入"的契约
 * （P1a-05 CI 连坐修复）——{@code ingest.enabled=false} 时上下文必须照常装配
 * （knowledge-ingestion-adr §决策 9），手动重嵌在通道缺席时先判后写。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("KnowledgeDocLifecycleService：入库通道缺席时的语义")
class KnowledgeDocLifecycleServiceTest {

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final UUID DOC_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    @Mock
    private KbDocRepository docRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private KnowledgeVectorizeStream vectorizeStream;

    private KbDocEntity doc;

    @BeforeEach
    void setUp() {
        doc = new KbDocEntity();
        doc.setId(DOC_ID);
    }

    @Test
    @DisplayName("通道关闭：revectorize 抛 2307 且不触碰仓库（先判后写，不产生 PENDING 悬挂）")
    void revectorizeWithoutChannelFailsFastBeforeAnyWrite() {
        KnowledgeDocLifecycleService service = new KnowledgeDocLifecycleService(
            docRepository, Optional.empty(), Optional.empty(), transactionManager);

        assertThatThrownBy(() -> service.revectorize(USER, DOC_ID.toString()))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode())
                    .isEqualTo(ErrorCode.KB_DOC_ENQUEUE_FAILED.getCode()))
            .hasMessageContaining("未启用");
        verifyNoInteractions(docRepository);
    }

    @Test
    @DisplayName("通道存在：重排落库并投递，语义与 Optional 化之前一致")
    void revectorizeWithChannelKeepsExistingSemantics() {
        KnowledgeDocLifecycleService service = new KnowledgeDocLifecycleService(
            docRepository, Optional.empty(), Optional.of(vectorizeStream), transactionManager);
        when(docRepository.findByIdAndUserId(DOC_ID, UUID.fromString(USER))).thenReturn(Optional.of(doc));
        when(docRepository.requeueForRevectorize(eq(DOC_ID), any())).thenReturn(1);
        when(vectorizeStream.send(DOC_ID)).thenReturn(true);

        service.revectorize(USER, DOC_ID.toString());

        verify(docRepository).requeueForRevectorize(eq(DOC_ID), any());
        verify(docRepository, never()).markFailedIfPending(any(), any(), any());
    }
}
