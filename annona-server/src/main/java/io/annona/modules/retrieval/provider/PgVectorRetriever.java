package io.annona.modules.retrieval.provider;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.search.Tokenizer;
import io.annona.common.search.VectorLiterals;
import io.annona.modules.retrieval.hybrid.RrfFusion;
import io.annona.spi.dto.RetrievalHit;
import io.annona.spi.dto.RetrievalMode;
import io.annona.spi.dto.RetrievalQuery;
import io.annona.spi.model.EmbeddingProvider;
import io.annona.spi.retrieval.Retriever;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * pgvector 全含检索后端（P1a-07；契约见 {@code annona-spi} 的 Retriever javadoc）。
 *
 * <p>语义通道（HNSW + 余弦）与关键词通道（{@code tsv} 主、{@code pg_trgm} 包含匹配兜底）
 * 都在本类内完成，再经 {@link RrfFusion} 融合——换后端时整套机器一起换，上层不需要知道
 * 有几条通道，也不用替自定义后端重实现融合。
 *
 * <p>读侧直接用 JdbcTemplate 而不借 knowledge 的 JPA 仓储：跨模块 import 禁止
 * （AGENTS.md §4），且 {@code embedding}/{@code tsv} 两列本就刻意不映射进实体（V4/V5 列注释）。
 * 正文与偏移不在返回体里——消费方按 {@code chunkId} 批量回查（{@code RetrievalHit} 契约）。
 */
@Component
@ConditionalOnProperty(prefix = "annona.retrieval", name = "backend",
    havingValue = "pgvector", matchIfMissing = true)
public class PgVectorRetriever implements Retriever {

    /** 每通道取的候选数 = topK × 本值（沿用 🅜 实测口径，给融合留出互补空间）。 */
    static final int CANDIDATE_MULTIPLIER = 2;

    /** snippet 截断长度：只够列表展示与人工核对；喂 LLM 的完整正文由 qa 按 chunkId 回查。 */
    static final int SNIPPET_CHARS = 200;

    private static final Logger log = LoggerFactory.getLogger(PgVectorRetriever.class);

    private static final String SELECT_COLUMNS =
        "SELECT c.doc_id::text AS doc_id, c.id::text AS chunk_id,"
            + " left(c.content, " + SNIPPET_CHARS + ") AS snippet";

    private final JdbcTemplate jdbc;
    private final Tokenizer tokenizer;
    /** 未配置向量化模型时为空：语义通道直接不产出候选（不报错，退成纯关键词）。 */
    private final Optional<EmbeddingProvider> embeddingProvider;

    public PgVectorRetriever(JdbcTemplate jdbc, Tokenizer tokenizer,
        Optional<EmbeddingProvider> embeddingProvider) {
        this.jdbc = jdbc;
        this.tokenizer = tokenizer;
        this.embeddingProvider = embeddingProvider;
    }

    @Override
    public String backend() {
        return "pgvector";
    }

    /**
     * 执行一次检索。
     *
     * <p>前置条件：{@code query.userId} 来自已鉴权 Principal（越权面在 SQL 的 user_id 谓词里
     * 就关掉了）；三档 mode 共用同一套可见性谓词，否则"混合 vs 纯向量"比的是两个候选集。
     *
     * <p>失败语义：SQL 与 embedding 调用异常包装成 {@code BusinessException(RETRIEVAL_FAILED)}
     * （SPI 约定不裸抛）；无命中返回<b>空列表</b>——"凭什么没找到"由上层 diagnostics 回答。
     *
     * <p>副作用：语义通道调一次 embedding（外部 HTTP，<b>不在任何事务内</b>，铁律）；
     * 本方法只读不写。
     */
    @Override
    public List<RetrievalHit> retrieve(RetrievalQuery query) {
        RetrievalMode mode = query.mode();
        List<RetrievalHit> semantic =
            mode == RetrievalMode.KEYWORD ? List.of() : semanticChannel(query);
        List<RetrievalHit> keyword =
            mode == RetrievalMode.SEMANTIC ? List.of() : keywordChannel(query);
        if (semantic.isEmpty() && keyword.isEmpty()) {
            return List.of();
        }
        // 通道顺序即并列时的优先序：语义在前（沿用 🅜 的降级顺序）
        return RrfFusion.fuse(List.of(semantic, keyword), query.topK());
    }

    private List<RetrievalHit> semanticChannel(RetrievalQuery query) {
        if (embeddingProvider.isEmpty()) {
            log.debug("embedding 未配置，跳过语义通道 userId={}", query.userId());
            return List.of();
        }
        float[] vector;
        try {
            // 查询向量不记账：检索读路径无稳定会话宿主，用量归属不成立（如实声明，
            // metering-adr 批 3 修订）；返回结果的 usage 在此刻意丢弃
            vector = embeddingProvider.get().embed(List.of(query.text())).vectors().get(0);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.RETRIEVAL_FAILED,
                "查询向量化失败，无法执行语义检索：" + e.getMessage());
        }
        String sql = SELECT_COLUMNS + " FROM kb_doc_chunk c JOIN kb_doc d ON d.id = c.doc_id"
            + whereBase(query)
            + " AND c.embedding IS NOT NULL"
            + " ORDER BY c.embedding <=> ?::vector LIMIT ?";
        List<Object> args = baseArgs(query);
        args.add(VectorLiterals.of(vector));
        args.add(query.topK() * CANDIDATE_MULTIPLIER);
        return run(sql, args);
    }

    /**
     * 关键词通道：{@code tsv} 主，命中 0 行才走包含匹配兜底。
     *
     * <p>兜底条件是<b>零参数规则</b>（"0 行"而不是"不够多"），这样 P1a 不引入任何阈值，
     * 也让评测能单独归因兜底路径的贡献。
     */
    private List<RetrievalHit> keywordChannel(RetrievalQuery query) {
        String raw = query.text().trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        String tsquery = tokenizer.toTsQueryString(raw);
        if (!tsquery.isEmpty()) {
            List<RetrievalHit> rows = run(SELECT_COLUMNS
                + " FROM kb_doc_chunk c JOIN kb_doc d ON d.id = c.doc_id" + whereBase(query)
                + " AND c.tsv @@ to_tsquery('simple'::regconfig, ?)"
                + " ORDER BY ts_rank(c.tsv, to_tsquery('simple'::regconfig, ?)) DESC, c.id LIMIT ?",
                appended(baseArgs(query), tsquery, tsquery, query.topK() * CANDIDATE_MULTIPLIER));
            if (!rows.isEmpty()) {
                return rows;
            }
        }
        // 谓词必须是 ILIKE 包含匹配而不是相似度运算符 %：长正文对短 query 的 trigram 相似度
        // 永远低于默认 0.3 阈值，写成 % 就是个恒不命中且不报错的谓词（keyword ADR 修订第 5 条）
        return run(SELECT_COLUMNS
            + " FROM kb_doc_chunk c JOIN kb_doc d ON d.id = c.doc_id" + whereBase(query)
            + " AND c.content ILIKE ? ORDER BY c.id LIMIT ?",
            appended(baseArgs(query), "%" + escapeLike(raw) + "%",
                query.topK() * CANDIDATE_MULTIPLIER));
    }

    /**
     * 两条通道共用的可见性谓词。
     *
     * <p>{@code embedding_model} 那一行是批 1 定下的契约（V5 §5 列注释）：漏掉它，换模型后的
     * 新向量会与旧向量混排，fake 与真模型切换时命中集合静默变空，而报告上只显示"召回低"。
     * <b>provider 缺席时不加这一行</b>：此时没有可比的新向量，加了会把关键词通道一起打死
     * （现象是"配了库却搜不到任何文字"）。
     */
    private String whereBase(RetrievalQuery query) {
        StringBuilder where = new StringBuilder(
            " WHERE d.user_id = ?::uuid AND d.status = 'READY'");
        if (embeddingProvider.isPresent()) {
            where.append(" AND d.embedding_model = ?");
        }
        if (!query.kbDocIds().isEmpty()) {
            where.append(" AND c.doc_id IN (")
                .append(String.join(", ", Collections.nCopies(query.kbDocIds().size(), "?::uuid")))
                .append(")");
        }
        return where.toString();
    }

    private List<Object> baseArgs(RetrievalQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.userId());
        // 与写入方同源：kb_doc.embedding_model 落的就是 provider.name()（V5 §5）。
        // 两处都从 provider 取，杜绝"一侧读配置、一侧读 provider"造成整体过滤落空
        if (embeddingProvider.isPresent()) {
            args.add(embeddingProvider.get().name());
        }
        args.addAll(query.kbDocIds());
        return args;
    }

    private List<Object> appended(List<Object> base, Object... extra) {
        List<Object> args = new ArrayList<>(base);
        Collections.addAll(args, extra);
        return args;
    }

    private List<RetrievalHit> run(String sql, List<Object> args) {
        try {
            // score 先占 0.0：通道内名次才是 RRF 的输入，通道分数不参与融合（可比性假设）
            return jdbc.query(sql, (rs, i) -> new RetrievalHit(rs.getString("doc_id"),
                rs.getString("chunk_id"), rs.getString("snippet"), 0.0), args.toArray());
        } catch (RuntimeException e) {
            log.error("检索 SQL 执行失败 sql={}", sql, e);
            throw new BusinessException(ErrorCode.RETRIEVAL_FAILED,
                "检索执行失败：" + e.getMessage());
        }
    }

    /** LIKE 模式串转义：不转义 {@code %} 与 {@code _} 的话，含下划线的问题会退化成通配匹配。 */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
