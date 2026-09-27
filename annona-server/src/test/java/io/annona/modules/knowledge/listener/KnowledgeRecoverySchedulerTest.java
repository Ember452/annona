package io.annona.modules.knowledge.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.annona.common.stream.TaskStreamPort;
import io.annona.modules.knowledge.entity.KbDocEntity;
import io.annona.modules.knowledge.listener.KnowledgeRecoveryScheduler;
import io.annona.modules.knowledge.repository.KbDocRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 恢复调度单测——场景移植 🅖 VectorizeRecoverySchedulerTest：touch 原子去重、
 * 计数上限判死、在途代次回收、开关关闭不扫描。
 */
@DisplayName("KnowledgeRecoveryScheduler：入库恢复调度")
@ExtendWith(MockitoExtension.class)
class KnowledgeRecoverySchedulerTest {

    /** 测试用 no-op 事务管理器：TransactionTemplate 只需要 getTransaction/commit 可调，回调照常执行。 */
    private static final PlatformTransactionManager TX_MGR = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    };


    @Mock
    private KbDocRepository docRepository;
    @Mock
    private TaskStreamPort taskStreamPort;

    private KnowledgeRecoveryScheduler scheduler;
    private KnowledgeRecoveryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new KnowledgeRecoveryProperties();
        scheduler = new KnowledgeRecoveryScheduler(docRepository, taskStreamPort, properties,
            TX_MGR);
    }

    private KbDocEntity pendingDoc(int recoveryCount) {
        KbDocEntity doc = new KbDocEntity();
        doc.setId(UUID.randomUUID());
        doc.setStatus(KbDocEntity.STATUS_PENDING);
        doc.setRecoveryCount(recoveryCount);
        return doc;
    }

    @Test
    @DisplayName("开关关闭 → 不扫描不投递")
    void disabledSkipsEverything() {
        properties.setEnabled(false);

        scheduler.recover();

        verifyNoInteractions(docRepository, taskStreamPort);
    }

    @Test
    @DisplayName("PENDING 超时：touch 成功且未达上限 → 补投一次（借 🅖）")
    void pendingStaleResends() {
        KbDocEntity doc = pendingDoc(0);
        when(docRepository.findRecoveryCandidates(any(), any())).thenReturn(List.of(doc));
        when(docRepository.touchPendingForRecovery(eq(doc.getId()), any(), any())).thenReturn(1);

        scheduler.recover();

        verify(taskStreamPort).send(any(), any());
    }

    @Test
    @DisplayName("touch 未推进（已被其他调度实例恢复）→ 不补投（原子去重，借 🅖）")
    void touchedByOtherInstanceSkipsResend() {
        KbDocEntity doc = pendingDoc(0);
        when(docRepository.findRecoveryCandidates(any(), any())).thenReturn(List.of(doc));
        when(docRepository.touchPendingForRecovery(eq(doc.getId()), any(), any())).thenReturn(0);

        scheduler.recover();

        verify(taskStreamPort, never()).send(any(), any());
    }

    @Test
    @DisplayName("恢复计数达上限 → 判 FAILED 不再补投（借 🅖）")
    void recoveryExhaustedMarksFailed() {
        KbDocEntity doc = pendingDoc(2); // +1 = 3 = max
        when(docRepository.findRecoveryCandidates(any(), any())).thenReturn(List.of(doc));
        when(docRepository.touchPendingForRecovery(eq(doc.getId()), any(), any())).thenReturn(1);

        scheduler.recover();

        verify(docRepository).markRecoveryExhausted(eq(doc.getId()), eq(3), anyString(), any());
        verify(taskStreamPort, never()).send(any(), any());
    }

    @Test
    @DisplayName("在途超时（心跳丢失）：代次匹配重置成功 → 回 PENDING 并补投")
    void inFlightStaleResetsAndResends() {
        KbDocEntity doc = pendingDoc(0);
        doc.setStatus(KbDocEntity.STATUS_EMBEDDING);
        doc.setAttemptId("attempt-1");
        // 两次扫描分别 stub：PENDING 扫描为空，在途扫描返回该文档
        when(docRepository.findRecoveryCandidates(eq(List.of(KbDocEntity.STATUS_PENDING)), any()))
            .thenReturn(List.of());
        when(docRepository.findRecoveryCandidates(eq(List.of(KbDocEntity.STATUS_PARSING,
                KbDocEntity.STATUS_CHUNKING, KbDocEntity.STATUS_EMBEDDING)), any()))
            .thenReturn(List.of(doc));
        when(docRepository.resetStaleToPending(eq(doc.getId()), eq("attempt-1"), any())).thenReturn(1);

        scheduler.recover();

        verify(taskStreamPort).send(any(), any());
        verify(docRepository, never()).touchPendingForRecovery(any(), any(), any());
    }

    @Test
    @DisplayName("在途重置失败（代次易主）→ 不补投，交给接管者")
    void inFlightResetFailureSkipsResend() {
        KbDocEntity doc = pendingDoc(0);
        doc.setStatus(KbDocEntity.STATUS_PARSING);
        doc.setAttemptId("attempt-old");
        when(docRepository.findRecoveryCandidates(eq(List.of(KbDocEntity.STATUS_PENDING)), any()))
            .thenReturn(List.of());
        when(docRepository.findRecoveryCandidates(eq(List.of(KbDocEntity.STATUS_PARSING,
                KbDocEntity.STATUS_CHUNKING, KbDocEntity.STATUS_EMBEDDING)), any()))
            .thenReturn(List.of(doc));
        when(docRepository.resetStaleToPending(eq(doc.getId()), eq("attempt-old"), any())).thenReturn(0);

        scheduler.recover();

        verify(taskStreamPort, never()).send(any(), any());
    }
}
