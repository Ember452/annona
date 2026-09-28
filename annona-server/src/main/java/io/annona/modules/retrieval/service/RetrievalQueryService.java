package io.annona.modules.retrieval.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 检索编排（P1a-07）：校验入参 → 调后端 → 计时 → 补空命中诊断。
 *
 * <p>诊断在本层而不是后端里：后端只负责"给一个查询，返回一组命中"（SPI 契约），
 * 而"库里有多少文档、当前模型身份是什么"是 annona 侧的产品语义（要给用户看的话术）。
 */
@Service
public class RetrievalQueryService {

    /** 默认取 4 条（设计文档 §7：RRF 后截断 TopK=4）。配置化推迟到有真实问答数据后。 */
    static final int DEFAULT_TOP_K = 4;

    /** 上限保护：一次最多 20 条，防止把 topK 当"翻页参数"扫全库（评测脚本用的是默认值）。 */
    static final int MAX_TOP_K = 20;

    private final Retriever retriever;
    private final JdbcTemplate jdbc;
    private final Optional<EmbeddingProvider> embeddingProvider;

    public RetrievalQueryService(Retriever retriever, JdbcTemplate jdbc,
        Optional<EmbeddingProvider> embeddingProvider) {
        this.retriever = retriever;
        this.jdbc = jdbc;
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * 执行一次检索。
     *
     * <p>前置条件：{@code userId} 来自已鉴权 Principal；检索只覆盖该用户的 READY 文档。
     *
     * <p>失败语义：查询文本空白 → {@code RETRIEVAL_QUERY_BLANK(2401)}；mode 取值非法 →
     * {@code BAD_REQUEST(1001)}；后端异常由后端包装成 {@code RETRIEVAL_FAILED(2400)}。
     * <b>无命中不是失败</b>：返回空 hits + diagnostics 解释原因。
     *
     * <p>事务边界：本方法<b>不开事务</b>——只读，且语义通道会调外部 embedding
     * （AGENTS.md §AI 规则：LLM 调用不得在事务内）。
     *
     * @param userId 检索发起人
     * @param request 请求体（query / topK / mode）
     * @return 命中列表与诊断；两者都非 null
     */
    public RetrievalResponse search(String userId, RetrievalRequest request) {
        String text = request.query() == null ? "" : request.query().trim();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.RETRIEVAL_QUERY_BLANK, "请输入要检索的问题");
        }
        int topK = request.topK() == null || request.topK() <= 0
            ? DEFAULT_TOP_K : Math.min(request.topK(), MAX_TOP_K);
        RetrievalMode mode = parseMode(request.mode());

        long started = System.nanoTime();
        List<RetrievalHit> hits = retriever.retrieve(
            new RetrievalQuery(text, topK, userId, List.of(), mode));
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        List<RetrievalResponse.Hit> views = hits.stream()
            .map(hit -> new RetrievalResponse.Hit(hit.docId(), hit.chunkId(), hit.score()))
            .toList();
        return new RetrievalResponse(tookMs, views, diagnose(userId, views.isEmpty()));
    }

    /**
     * 空命中归因。有命中时只回 {@link RetrievalMissReason#MATCHED} 并把两个计数置 0（省掉
     * 两次 count）：计数只在"要解释为何空手"时才有意义，有命中时多两次查询换不到任何东西。
     */
    private RetrievalResponse.Diagnostics diagnose(String userId, boolean emptyHits) {
        if (!emptyHits) {
            return new RetrievalResponse.Diagnostics(0, 0, RetrievalMissReason.MATCHED);
        }
        int readyDocs = count("SELECT count(*) FROM kb_doc WHERE user_id = ?::uuid "
            + "AND status = 'READY'", userId);
        if (readyDocs == 0) {
            return new RetrievalResponse.Diagnostics(0, 0, RetrievalMissReason.NO_READY_DOC);
        }
        if (embeddingProvider.isEmpty()) {
            // 模型未配置时语义通道整条不参与；对用户呈现为"身份不匹配"（同一个处置：配好模型或重建）
            return new RetrievalResponse.Diagnostics(readyDocs, 0, RetrievalMissReason.MODEL_MISMATCH);
        }
        int matched = count("SELECT count(*) FROM kb_doc WHERE user_id = ?::uuid "
            + "AND status = 'READY' AND embedding_model = ?", userId,
            embeddingProvider.get().name());
        if (matched == 0) {
            return new RetrievalResponse.Diagnostics(readyDocs, 0, RetrievalMissReason.MODEL_MISMATCH);
        }
        return new RetrievalResponse.Diagnostics(readyDocs, matched, RetrievalMissReason.NO_MATCH);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static RetrievalMode parseMode(String raw) {
        try {
            return RetrievalMode.parseOrDefault(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "mode 只接受 BOTH / SEMANTIC / KEYWORD，收到：" + raw);
        }
    }
}
