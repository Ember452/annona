package io.annona.modules.knowledge.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.parse.DocumentBlock;
import io.annona.common.parse.DocumentBlock.BlockType;
import io.annona.common.storage.ObjectStorage;
import io.annona.common.stream.TaskStreamPort;
import io.annona.modules.knowledge.entity.KbDocChunkEntity;
import io.annona.modules.knowledge.entity.KbDocEntity;
import io.annona.modules.knowledge.progress.KnowledgeProgressHub;
import io.annona.modules.knowledge.repository.KbDocChunkRepository;
import io.annona.modules.knowledge.repository.KbDocRepository;
import io.annona.spi.model.EmbeddingProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 向量化消费单测——场景移植 🅖 VectorizeStreamConsumerTest 的条件领取/跳过/重试语义
 * + annona 特有：分块落库、向量批量、进度推进、代次 fencing 的静默中止。
 */
@DisplayName("KnowledgeVectorizeService：向量化消费")
@ExtendWith(MockitoExtension.class)
class KnowledgeVectorizeServiceTest {

    @Mock
    private KbDocRepository docRepository;
    @Mock
    private KbDocChunkRepository chunkRepository;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private io.annona.common.parse.DocumentParser documentParser;
    @Mock
    private EmbeddingProvider embeddingProvider;
    @Mock
    private KnowledgeProgressHub progressHub;

    private KnowledgeVectorizeService service;

    private final UUID docId = UUID.randomUUID();
    private KbDocEntity doc;

    @BeforeEach
    void setUp() {
        service = new KnowledgeVectorizeService(docRepository, chunkRepository,
            Optional.of(objectStorage), documentParser, Optional.of(embeddingProvider), progressHub);
        doc = new KbDocEntity();
        doc.setId(docId);
        doc.setStatus(KbDocEntity.STATUS_PENDING);
        doc.setStorageKey("knowledge/2026/09/27/abc_a.md");
        doc.setOriginalFilename("a.md");
    }

    private void stubClaimAndParse() {
        lenient().when(docRepository.findById(docId)).thenReturn(Optional.of(doc));
        lenient().when(docRepository.tryMarkParsing(eq(docId), anyString(), any())).thenReturn(1);
        lenient().when(docRepository.tryMarkChunking(eq(docId), anyString(), any())).thenReturn(1);
        lenient().when(docRepository.tryMarkEmbedding(eq(docId), anyString(), anyInt(), any())).thenReturn(1);
        lenient().when(docRepository.markReady(eq(docId), anyString(), anyInt(), anyString(), any()))
            .thenReturn(1);
        lenient().when(docRepository.heartbeat(eq(docId), anyString(), any())).thenReturn(1);
        lenient().when(docRepository.markProgress(eq(docId), anyString(), anyInt(), any())).thenReturn(1);
        lenient().when(objectStorage.get(anyString())).thenReturn("你好世界".getBytes());
        lenient().when(documentParser.parse(any(), anyString()))
            .thenReturn(List.of(new DocumentBlock(BlockType.PARAGRAPH, null, "你好世界", 0, 4)));
        lenient().when(embeddingProvider.embed(any())).thenReturn(List.of(new float[] {0.1f, 0.2f}));
        lenient().when(embeddingProvider.name()).thenReturn("text-embedding-fake");
        KbDocChunkEntity row = new KbDocChunkEntity();
        row.setId(UUID.randomUUID());
        row.setChunkIndex(0);
        lenient().when(chunkRepository.findByDocIdOrderByChunkIndexAsc(docId)).thenReturn(List.of(row));
    }

    @Nested
    @DisplayName("跳过与领取")
    class SkipAndClaim {

        @Test
        @DisplayName("消息缺 docId / docId 非法 → ACK 丢弃（借 🅖）")
        void malformedMessageAcked() {
            assertThat(service.handle("1", Map.of(), 0)).isEqualTo(TaskStreamPort.Outcome.ACK);
            assertThat(service.handle("1", Map.of("docId", "not-a-uuid"), 0))
                .isEqualTo(TaskStreamPort.Outcome.ACK);
        }

        @Test
        @DisplayName("文档已删除 → ACK 丢弃不向量化（借 🅖）")
        void missingDocAcked() {
            when(docRepository.findById(docId)).thenReturn(Optional.empty());

            assertThat(service.handle("1", Map.of("docId", docId.toString()), 0))
                .isEqualTo(TaskStreamPort.Outcome.ACK);
        }

        @Test
        @DisplayName("文档已 READY（重复投递）→ ACK 丢弃")
        void readyDocAcked() {
            doc.setStatus(KbDocEntity.STATUS_READY);
            when(docRepository.findById(docId)).thenReturn(Optional.of(doc));

            assertThat(service.handle("1", Map.of("docId", docId.toString()), 0))
                .isEqualTo(TaskStreamPort.Outcome.ACK);
        }

        @Test
        @DisplayName("条件领取失败（其他实例已接手）→ ACK 不执行（借 🅖 tryMarkVectorProcessing）")
        void claimFailureAcked() {
            when(docRepository.findById(docId)).thenReturn(Optional.of(doc));
            when(docRepository.tryMarkParsing(eq(docId), anyString(), any())).thenReturn(0);

            assertThat(service.handle("1", Map.of("docId", docId.toString()), 0))
                .isEqualTo(TaskStreamPort.Outcome.ACK);
            verify(objectStorage, never()).get(anyString());
        }
    }

    @Nested
    @DisplayName("成功路径")
    class HappyPath {

        @Test
        @DisplayName("解析→分块→落库→分批嵌入→进度→READY 全链路，embedding 模型回写")
        void fullPipelineReachesReady() {
            stubClaimAndParse();

            TaskStreamPort.Outcome outcome = service.handle("1", Map.of("docId", docId.toString()), 0);

            assertThat(outcome).isEqualTo(TaskStreamPort.Outcome.ACK);
            verify(chunkRepository).deleteByDocId(docId);
            verify(chunkRepository).saveAll(any());
            verify(chunkRepository, atLeastOnce()).updateEmbedding(any(), eq("[0.1,0.2]"));
            verify(docRepository).markReady(eq(docId), anyString(), eq(1), eq("text-embedding-fake"), any());
            verify(progressHub, atLeastOnce()).publish(eq(docId), any());
        }
    }

    @Nested
    @DisplayName("失败与重试")
    class FailureAndRetry {

        @Test
        @DisplayName("解析为空（确定性失败）首次 → 条件重置回 PENDING 并 RETRY（借 🅖 重试语义）")
        void emptyParseRetries() {
            stubClaimAndParse();
            when(documentParser.parse(any(), anyString())).thenReturn(List.of());
            when(docRepository.resetStaleToPending(eq(docId), anyString(), any())).thenReturn(1);

            TaskStreamPort.Outcome outcome = service.handle("1", Map.of("docId", docId.toString()), 0);

            assertThat(outcome).isEqualTo(TaskStreamPort.Outcome.RETRY);
            verify(docRepository).resetStaleToPending(eq(docId), anyString(), any());
            verify(docRepository, never()).markReady(any(), anyString(), anyInt(), anyString(), any());
        }

        @Test
        @DisplayName("重试已达上限（retryCount=3）→ 条件判 FAILED 并 DEAD（借 🅖 markFailed）")
        void retriesExhaustedMarksFailed() {
            stubClaimAndParse();
            when(documentParser.parse(any(), anyString())).thenReturn(List.of());
            when(docRepository.resetStaleToPending(eq(docId), anyString(), any())).thenReturn(1);

            TaskStreamPort.Outcome outcome = service.handle("1", Map.of("docId", docId.toString()),
                KnowledgeVectorizeService.MAX_RETRY);

            assertThat(outcome).isEqualTo(TaskStreamPort.Outcome.DEAD);
            verify(docRepository).markFailedIfPending(eq(docId), anyString(), any());
        }

        @Test
        @DisplayName("重置失败（代次易主）→ DEAD 不重投，避免与接管者双写")
        void resetFailureGivesUpQuietly() {
            stubClaimAndParse();
            when(documentParser.parse(any(), anyString())).thenReturn(List.of());
            when(docRepository.resetStaleToPending(eq(docId), anyString(), any())).thenReturn(0);

            TaskStreamPort.Outcome outcome = service.handle("1", Map.of("docId", docId.toString()), 0);

            assertThat(outcome).isEqualTo(TaskStreamPort.Outcome.DEAD);
            verify(docRepository).markFailedIfPending(eq(docId), anyString(), any());
        }

        @Test
        @DisplayName("执行权丢失（代次被回收）→ ACK 静默中止，不重试不判死（fencing 语义）")
        void attemptLostAcked() {
            stubClaimAndParse();
            // CHUNKING 领取失败 = 其他实例已用新代次接管
            when(docRepository.tryMarkChunking(eq(docId), anyString(), any())).thenReturn(0);

            TaskStreamPort.Outcome outcome = service.handle("1", Map.of("docId", docId.toString()), 0);

            assertThat(outcome).isEqualTo(TaskStreamPort.Outcome.ACK);
            verify(docRepository, never()).markFailed(any(), anyString(), anyString(), any());
            verify(docRepository, never()).markFailedIfPending(any(), anyString(), any());
        }
    }
}
