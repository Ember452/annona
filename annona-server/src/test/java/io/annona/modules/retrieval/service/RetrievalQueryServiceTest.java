package io.annona.modules.retrieval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.modules.retrieval.dto.RetrievalMissReason;
import io.annona.modules.retrieval.dto.RetrievalRequest;
import io.annona.modules.retrieval.dto.RetrievalResponse;
import io.annona.spi.dto.RetrievalHit;
import io.annona.spi.dto.RetrievalMode;
import io.annona.spi.dto.RetrievalQuery;
import io.annona.spi.model.EmbeddingProvider;
import io.annona.spi.retrieval.Retriever;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 检索编排切片测试（Mockito，不接 PG）。重点验两件事：入参归一（topK / mode），以及
 * <b>空命中必须可解释</b>——那是 AGENTS.md §1"可解释优先于准确"在检索上的落点。
 */
@DisplayName("RetrievalQueryService：入参归一与空命中诊断")
@ExtendWith(MockitoExtension.class)
class RetrievalQueryServiceTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CURRENT_MODEL = "text-embedding-v3";

    private static final String READY_SQL =
        "SELECT count(*) FROM kb_doc WHERE user_id = ?::uuid AND status = 'READY'";
    private static final String MODEL_SQL = READY_SQL + " AND embedding_model = ?";

    @Mock
    private Retriever retriever;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private EmbeddingProvider provider;

    @Test
    @DisplayName("空白查询报 2401，不允许空查询扫全库")
    void blankQueryRejected() {
        assertThatThrownBy(() -> service().search(USER_ID, new RetrievalRequest("   ", 4, null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请输入要检索的问题");
    }

    @Test
    @DisplayName("mode 非法值转成 1001 业务错误，不是 500")
    void badModeRejected() {
        assertThatThrownBy(() -> service().search(USER_ID, new RetrievalRequest("问题", 4, "es")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("BOTH / SEMANTIC / KEYWORD");
    }

    @Test
    @DisplayName("topK 缺省走 4、超上限截到 20；mode 缺省归一为 BOTH；空白被裁掉")
    void normalizesTopKModeAndText() {
        when(retriever.retrieve(any())).thenReturn(List.of(hit("c1")));

        service().search(USER_ID, new RetrievalRequest("  事务传播  ", null, null));
        service().search(USER_ID, new RetrievalRequest("事务", 999, "SEMANTIC"));

        ArgumentCaptor<RetrievalQuery> captor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retriever, times(2)).retrieve(captor.capture());
        List<RetrievalQuery> sent = captor.getAllValues();
        assertThat(sent.get(0).topK()).isEqualTo(RetrievalQueryService.DEFAULT_TOP_K);
        assertThat(sent.get(0).mode()).isEqualTo(RetrievalMode.BOTH);
        assertThat(sent.get(0).text()).as("前后空白应被裁掉").isEqualTo("事务传播");
        assertThat(sent.get(1).topK()).isEqualTo(RetrievalQueryService.MAX_TOP_K);
        assertThat(sent.get(1).mode()).isEqualTo(RetrievalMode.SEMANTIC);
    }

    @Test
    @DisplayName("有命中时不做诊断计数：reason 恒为 MATCHED，两个计数留 0")
    void matchedSkipsDiagnosticCounts() {
        when(retriever.retrieve(any())).thenReturn(List.of(hit("c1"), hit("c2")));

        RetrievalResponse response = service().search(USER_ID, new RetrievalRequest("事务", 4, null));

        assertThat(response.hits()).hasSize(2);
        assertThat(response.diagnostics().reason()).isEqualTo(RetrievalMissReason.MATCHED);
        assertThat(response.diagnostics().readyDocs()).as("有命中时不该多跑两次 count").isZero();
    }

    @Nested
    @DisplayName("空命中三档归因")
    class EmptyHits {

        @Test
        @DisplayName("一篇 READY 文档都没有 → NO_READY_DOC")
        void noReadyDoc() {
            stubReady(0);

            assertThat(reason()).isEqualTo(RetrievalMissReason.NO_READY_DOC);
        }

        @Test
        @DisplayName("有 READY 但向量身份不匹配 → MODEL_MISMATCH（换模型或 fake 语料的典型现象）")
        void modelMismatch() {
            stubReady(7);
            stubMatched(0);

            assertThat(reason()).isEqualTo(RetrievalMissReason.MODEL_MISMATCH);
        }

        @Test
        @DisplayName("有可比对文档仍无命中 → NO_MATCH（资料里确实没写这件事）")
        void noMatch() {
            stubReady(7);
            stubMatched(5);

            RetrievalResponse.Diagnostics diagnostics = diagnose();
            assertThat(diagnostics.reason()).isEqualTo(RetrievalMissReason.NO_MATCH);
            assertThat(diagnostics.readyDocs()).isEqualTo(7);
            assertThat(diagnostics.modelMatchedDocs()).as("计数要回传，前端才能说清\"5 篇可比对\"")
                .isEqualTo(5);
        }

        private RetrievalMissReason reason() {
            return diagnose().reason();
        }

        private RetrievalResponse.Diagnostics diagnose() {
            when(retriever.retrieve(any())).thenReturn(List.of());
            return service().search(USER_ID, new RetrievalRequest("事务", 4, null)).diagnostics();
        }
    }

    private void stubReady(int value) {
        when(jdbc.queryForObject(eq(READY_SQL), eq(Integer.class), eq(USER_ID))).thenReturn(value);
    }

    private void stubMatched(int value) {
        when(jdbc.queryForObject(eq(MODEL_SQL), eq(Integer.class), eq(USER_ID), eq(CURRENT_MODEL)))
            .thenReturn(value);
    }

    private RetrievalQueryService service() {
        // provider.name() 只在 MODEL_SQL 那条路径上用，NO_READY_DOC 分支不碰它 → lenient
        lenient().when(provider.name()).thenReturn(CURRENT_MODEL);
        return new RetrievalQueryService(retriever, jdbc, Optional.of(provider));
    }

    private static RetrievalHit hit(String chunkId) {
        return new RetrievalHit("doc-1", chunkId, "snippet", 0.6);
    }
}
