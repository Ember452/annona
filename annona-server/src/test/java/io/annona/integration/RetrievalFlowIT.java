package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.common.search.VectorLiterals;
import io.annona.modules.retrieval.dto.RetrievalRequest;
import io.annona.modules.retrieval.dto.RetrievalResponse;
import io.annona.modules.retrieval.service.RetrievalQueryService;
import io.annona.spi.fake.FakeEmbeddingProvider;
import io.annona.spi.model.EmbeddingProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 混合检索三模式在<b>真实 PG + pgvector</b> 上的行为闸门（P1a-07）。
 *
 * <p>与 {@link RetrievalSchemaIT} 的分工：那个类验"V5 建出来的东西能查"，本类验"三档各自
 * 返回什么、可见性谓词真的在挡东西"。融合算法本身在 {@code RrfFusionTest} 表驱动覆盖，不重复。
 *
 * <p><b>为什么用 fake embedding</b>：语义通道的<b>质量</b>由 P1a-09 的 Recall@K 说话，本类
 * 要的是<b>形状</b>——命中集合、隔离谓词、分数区间。所以只断言"返回了几条、分数在 [0,1]、
 * 不该出现的文档一条都不出现"，<b>不断言谁排第一</b>（fake 向量与语义无关，排第一没有含义）。
 *
 * <p>机检的是三条静默故障：漏 {@code embedding_model} 谓词（换模型后新旧向量混排，指标只是
 * "差一点"）、漏 {@code user_id} 谓词（跨用户泄露）、关键词通道退化成恒不命中的谓词
 * （trgm 相似度写法，见 keyword ADR 修订第 5 条）。
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("混合检索三模式：命中集合与可见性隔离（真实 PG，CI 才跑）")
class RetrievalFlowIT {

    /** 同用户下另一份"旧模型"文档，用来验证向量身份过滤。 */
    private static final String STALE_MODEL = "some-older-embedding-model";

    /** SEMANTIC 档只有语义通道参与，归一化分数上限就是 0.5（见 RetrievalHit 契约）。 */
    private static final double SINGLE_CHANNEL_CAP = 0.5;

    @Autowired
    private RetrievalQueryService queryService;
    @Autowired
    private EmbeddingProvider embeddingProvider;
    @Autowired
    private JdbcTemplate jdbc;

    private String userId;
    private String ownDocId;
    private String staleDocId;
    private String foreignDocId;

    /** ownDoc 下的三个块：① tsv 能命中 ② 只有正文含 CAS（走包含兜底）③ 无向量。 */
    private String tokenChunkId;
    private String symbolChunkId;
    private String noVectorChunkId;
    private String staleChunkId;
    private String foreignChunkId;

    /** 内存 fake 向量（1024 维，与 V4 向量列对齐）。 */
    @TestConfiguration
    static class Fakes {
        @Bean
        EmbeddingProvider embeddingProvider() {
            return new FakeEmbeddingProvider();
        }
    }

    @BeforeEach
    void seedCorpus() {
        userId = insertUser();
        String otherUserId = insertUser();
        ownDocId = insertDoc(userId, embeddingProvider.name());
        staleDocId = insertDoc(userId, STALE_MODEL);
        foreignDocId = insertDoc(otherUserId, embeddingProvider.name());

        tokenChunkId = insertChunk(ownDocId, 0, "Redisson 的看门狗会周期性续期锁的过期时间",
            "redisson 看门狗 续期 锁 过期", true);
        symbolChunkId = insertChunk(ownDocId, 1, "这段只讲 CAS，写作 Compare-And-Swap，不提锁",
            "缩写 比较 交换", true);
        noVectorChunkId = insertChunk(ownDocId, 2, "没有向量的段落，Redisson 看门狗 续期",
            "redisson 看门狗 续期 段落", false);
        staleChunkId = insertChunk(staleDocId, 0, "旧模型向量也提到 Redisson 看门狗 续期",
            "redisson 看门狗 续期", true);
        foreignChunkId = insertChunk(foreignDocId, 0, "别人的 Redisson 看门狗 续期 说明",
            "redisson 看门狗 续期 说明", true);
    }

    @Test
    @DisplayName("SEMANTIC：候选只来自本人且向量身份匹配的文档，分数不超过单通道上限")
    void semanticModeRespectsVisibilityPredicates() {
        RetrievalResponse response = search("Redisson 看门狗 续期", "SEMANTIC");

        assertThat(response.hits()).as("ownDoc 有两个带向量的块，语义通道应有候选").isNotEmpty();
        assertThat(chunkIds(response))
            .as("语义候选只能来自身份匹配、且真的有向量的块")
            .allSatisfy(chunkId -> assertThat(chunkId).isIn(tokenChunkId, symbolChunkId));
        assertThat(chunkIds(response)).doesNotContain(noVectorChunkId, staleChunkId, foreignChunkId);
        assertThat(response.hits()).allSatisfy(hit -> assertThat(hit.score())
            .isBetween(0.0, SINGLE_CHANNEL_CAP + 1e-9));
    }

    @Test
    @DisplayName("KEYWORD：tsv 命中主路径；正文专有名词走包含兜底（相似度运算符做不到）")
    void keywordModeHitsTokensAndFallsBackToContainment() {
        assertThat(chunkIds(search("看门狗 续期", "KEYWORD"))).contains(tokenChunkId);

        List<String> symbolHits = chunkIds(search("CAS", "KEYWORD"));
        assertThat(symbolHits)
            .as("CAS 不在任何行的 tokens 里，只有 ILIKE 包含匹配能把它捞回来")
            .contains(symbolChunkId);
        assertThat(symbolHits).doesNotContain(foreignChunkId, staleChunkId);
    }

    @Test
    @DisplayName("BOTH：并集去重、条数不超 topK，旧身份与跨用户的块一条都不出现")
    void bothModeUnionsAndIsolates() {
        List<String> hits = chunkIds(search("Redisson 看门狗 续期", "BOTH"));

        assertThat(hits).contains(tokenChunkId, noVectorChunkId);
        assertThat(hits).doesNotHaveDuplicates();
        assertThat(hits).hasSizeLessThanOrEqualTo(RetrievalQueryService.DEFAULT_TOP_K);
        assertThat(hits).doesNotContain(staleChunkId, foreignChunkId);
    }

    @Test
    @DisplayName("向量身份全不匹配时报 MODEL_MISMATCH，而不是看起来正常的空结果")
    void emptyResultIsExplainable() {
        jdbc.update("UPDATE kb_doc SET embedding_model = ? WHERE id = ?::uuid",
            "no-doc-has-this-model", UUID.fromString(ownDocId));

        RetrievalResponse.Diagnostics diagnostics =
            search("Redisson 看门狗 续期", "SEMANTIC").diagnostics();

        assertThat(diagnostics.readyDocs()).as("本人 READY 文档：ownDoc + staleDoc").isEqualTo(2);
        assertThat(diagnostics.modelMatchedDocs()).isZero();
        assertThat(diagnostics.reason().name())
            .as("空命中必须说清是身份不匹配，而不是让用户以为资料里没写")
            .isEqualTo("MODEL_MISMATCH");
    }

    private List<String> chunkIds(RetrievalResponse response) {
        return response.hits().stream().map(RetrievalResponse.Hit::chunkId).toList();
    }

    private RetrievalResponse search(String query, String mode) {
        return queryService.search(userId, new RetrievalRequest(query, null, mode));
    }

    private String insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, email, password_hash, status, role) "
                + "VALUES (?, ?, ?, 'ACTIVE', 'USER')",
            id, "flow-" + UUID.randomUUID().toString().substring(0, 8) + "@annona.local",
            "flow-no-login");
        return id.toString();
    }

    private String insertDoc(String owner, String model) {
        UUID docId = UUID.randomUUID();
        UUID directionId = UUID.randomUUID();
        jdbc.update("INSERT INTO direction (id, key, name, origin) VALUES (?, ?, ?, 'SKILL_BUILTIN')",
            directionId, "flow-" + UUID.randomUUID().toString().substring(0, 8), "检索流程方向");
        jdbc.update("INSERT INTO kb_doc (id, user_id, direction_id, file_hash, name,"
            + " original_filename, file_size, content_type, storage_key, status, analyzer_version,"
            + " embedding_model, chunk_count, total_chunks, processed_chunks)"
            + " VALUES (?, ?::uuid, ?::uuid, ?, ?, ?, 1024, 'text/markdown', ?, 'READY', ?, ?, 1, 1, 1)",
            docId, UUID.fromString(owner), directionId, "hash-" + docId, "flow.md", "flow.md",
            "knowledge/flow/" + docId, "chunker-flow-v1", model);
        return docId.toString();
    }

    /**
     * 落一个分块。{@code withEmbedding=false} 用来造"tokens 在、向量为空"的行——
     * 语义通道必须把它挡在候选之外（{@code embedding IS NOT NULL} 谓词）。
     */
    private String insertChunk(String doc, int index, String content, String tokens,
        boolean withEmbedding) {
        UUID chunkId = UUID.randomUUID();
        List<Object> args = new ArrayList<>(List.of(chunkId, UUID.fromString(doc), index,
            content.length(), content, "hash-" + chunkId, tokens, "jieba-1.0.2-v1"));
        String columns = "id, doc_id, chunk_index, heading_path, char_start, char_end, content,"
            + " content_hash, tokens, tokenizer_version";
        String placeholders = "?, ?::uuid, ?, '', 0, ?, ?, ?, ?, ?";
        if (withEmbedding) {
            columns += ", embedding";
            placeholders += ", ?::vector";
            args.add(VectorLiterals.of(embeddingProvider.embed(List.of(content)).vectors().get(0)));
        }
        jdbc.update("INSERT INTO kb_doc_chunk (" + columns + ") VALUES (" + placeholders + ")",
            args.toArray());
        return chunkId.toString();
    }
}
