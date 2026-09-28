package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V5 关键词通道的 SQL 语义烟测（P1a-07；依据 retrieval-hybrid-adr §后果与约束）。
 *
 * <p><b>为什么存在本类</b>：本机无 Docker，且"装本机原生 PG 换快反馈"已被明确否决
 * （AGENTS.md §8.3），于是 STORED 生成列能否建、{@code to_tsquery} 能否命中、trgm 兜底谓词、
 * {@code ts_rank} 能否排序、两个 GIN 是否存在——这五件事只能在 CI 里暴露。把它们收进同一个
 * IT，等于把小批 A 的返工压进<b>一次</b>推送而不是五轮。
 *
 * <p>本类不验检索编排与融合（那是 {@code RetrievalFlowIT}），只验"V5 建出来的东西真能查"。
 *
 * <p><b>trgm 兜底为什么用 {@code ILIKE} 而不是相似度运算符 {@code %}</b>（实测前的算术结论，
 * 本类的第 3 个用例就是它的裁判）：{@code similarity(a,b) = 2·|T(a)∩T(b)| / (|T(a)|+|T(b)|)}，
 * 分母由两侧全部 trigram 数决定。分块正文动辄两三十字到几百字，query 只有几个字符，
 * 比值会稳定落在 0.05 量级，<b>永远够不到 {@code pg_trgm.similarity_threshold} 的默认 0.3</b>
 * ——用 {@code %} 写兜底等于写了一个恒不命中的谓词，而且它不报错。{@code gin_trgm_ops}
 * 索引同样支持 {@code ILIKE '%x%'} 的包含匹配，那才是"专有名词在正文里出现过"的语义。
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("V5 关键词通道 SQL 烟测（真实 PG，一次撞掉五个未知数）")
class RetrievalSchemaIT {

    private static final String TOKENIZER_VERSION = "it-smoke-v1";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("生成列由 tokens 派生出非空 tsv，且 DDL 上就是 ALWAYS 派生列（ADR §决策 2 的不变量）")
    void generatedColumnDerivesFromTokens() {
        Chunk chunk = seedChunk("Redisson 的可重入锁实现原理与看门狗续期机制",
            "Redisson 可重入 锁 实现 原理 看门狗 续期");

        String tsv = jdbc.queryForObject(
            "SELECT tsv::text FROM kb_doc_chunk WHERE id = ?", String.class, chunk.id());
        assertThat(tsv).as("生成列必须真派生出 lexeme（空串说明 to_tsvector 没吃到 tokens）")
            .isNotBlank()
            .containsIgnoringCase("redisson")
            .contains("可重入");

        // 不变量用 information_schema 断言而不是"写一下看报不报错"：后者的异常类型
        // 走 Spring Translator 的逐步兼容链，猜不得（SQLSTATE 0GL02 无专门映射）。
        Map<String, Object> column = jdbc.queryForMap(
            "SELECT is_generated, generation_expression FROM information_schema.columns "
                + "WHERE table_name = 'kb_doc_chunk' AND column_name = 'tsv'");
        assertThat(column.get("is_generated"))
            .as("tsv 必须是应用写不进去的派生列，这样 tokens 才是单一真相源")
            .isEqualTo("ALWAYS");
        assertThat(String.valueOf(column.get("generation_expression")))
            .contains("to_tsvector")
            .contains("'simple'::regconfig")
            .contains("tokens");
    }

    @Test
    @DisplayName("to_tsquery('simple', ...) 命中分词后的中文行（config 固定 simple 不再切词）")
    void tsqueryHitsTokenizedRow() {
        Chunk hit = seedChunk("Spring 事务传播行为与自调用失效的常见原因",
            "Spring 事务 传播 行为 自调用 失效");
        seedChunk("JVM 垃圾回收器的分代与停顿时间权衡", "JVM 垃圾 回收 分代 停顿 权衡");

        List<UUID> matched = jdbc.query(
            "SELECT id FROM kb_doc_chunk WHERE id = ? AND tsv @@ to_tsquery('simple'::regconfig, ?)",
            (rs, i) -> rs.getObject(1, UUID.class), hit.id(), "Spring & 事务 & 失效");

        assertThat(matched).containsExactly(hit.id());
    }

    @Test
    @DisplayName("trgm 兜底走 ILIKE 包含匹配命中；相似度运算符 % 在同一行上不命中")
    void trgmFallbackMatchesSubstringNotSimilarity() {
        Chunk chunk = seedChunk("CAS 与 volatile 在无锁队列里的配合使用方式",
            "CAS volatile 无锁 队列 配合 使用");

        Integer byIlike = jdbc.queryForObject(
            "SELECT count(*) FROM kb_doc_chunk WHERE id = ? AND content ILIKE ?", Integer.class,
            chunk.id(), "%CAS%");
        assertThat(byIlike).as("兜底谓词必须能命中专有名词").isEqualTo(1);

        Integer bySimilarity = jdbc.queryForObject(
            "SELECT count(*) FROM kb_doc_chunk WHERE id = ? AND content % ?::text", Integer.class,
            chunk.id(), "CAS");
        assertThat(bySimilarity)
            .as("算术结论：%s 对长正文永远低于 similarity_threshold，写成兜底就是恒不命中的谓词", "content % q")
            .isZero();
    }

    @Test
    @DisplayName("ts_rank 可作为关键词通道的 ORDER BY 依据（命中多的行排在前）")
    void tsRankOrdersCandidates() {
        Chunk more = seedChunk("Redisson 分布式锁 与 Redisson 看门狗 与 Redisson 读写锁",
            "Redisson 分布式 锁 看门狗 读写锁");
        Chunk fewer = seedChunk("Redisson 只是一个客户端实现", "Redisson 客户端 实现");

        List<UUID> ordered = jdbc.query(
            "SELECT id FROM kb_doc_chunk WHERE id IN (?, ?)"
                + " AND tsv @@ to_tsquery('simple'::regconfig, ?)"
                + " ORDER BY ts_rank(tsv, to_tsquery('simple'::regconfig, ?)) DESC, id",
            (rs, i) -> rs.getObject(1, UUID.class), more.id(), fewer.id(),
            "Redisson | 锁", "Redisson | 锁");

        assertThat(ordered).as("两行都应命中，且 rank 高的在前").containsExactly(more.id(), fewer.id());
    }

    @Test
    @DisplayName("两个 GIN 索引都在（tsv 主通道、content trgm 兜底）")
    void bothGinIndexesExist() {
        List<String> defs = jdbc.queryForList(
            "SELECT indexdef FROM pg_indexes WHERE tablename = 'kb_doc_chunk' AND indexname IN "
                + "('idx_kb_doc_chunk_tsv', 'idx_kb_doc_chunk_content_trgm')", String.class);

        assertThat(defs).hasSize(2);
        // indexdef 里只有列名（tsv 是普通存储列，表达式已落在 DDL 里），不要断言 to_tsvector
        String joined = String.join(" | ", defs);
        assertThat(joined).contains("gin").contains("(tsv)").contains("gin_trgm_ops");
    }

    @Test
    @DisplayName("tokens 为 NULL 的老文档不被关键词通道命中（无回填机器的既成事实）")
    void legacyRowWithoutTokensIsNotMatched() {
        Chunk legacy = seedChunk("V5 之前入库的老正文，含 Redisson 字样", null);

        Integer matched = jdbc.queryForObject(
            "SELECT count(*) FROM kb_doc_chunk WHERE id = ? AND tsv @@ to_tsquery('simple'::regconfig, ?)",
            Integer.class, legacy.id(), "Redisson");
        assertThat(matched).as("老行 tsv 为空 → 关键词通道不参与，由文档行的\"重建\"入口补 tokens")
            .isZero();
    }

    /** 种一篇 READY 文档下的一个分块；{@code tokens} 传 null 模拟 V5 之前的老行。 */
    private Chunk seedChunk(String content, String tokens) {
        UUID docId = insertDoc();
        UUID chunkId = UUID.randomUUID();
        jdbc.update("INSERT INTO kb_doc_chunk (id, doc_id, chunk_index, heading_path, char_start, "
                + "char_end, content, content_hash, tokens, tokenizer_version) "
                + "VALUES (?, ?, 0, '', 0, ?, ?, ?, ?, ?)",
            chunkId, docId, content.length(), content, "sha-" + chunkId, tokens,
            tokens == null ? null : TOKENIZER_VERSION);
        return new Chunk(chunkId);
    }

    private UUID insertDoc() {
        UUID docId = UUID.randomUUID();
        UUID userId = insertUser();
        UUID directionId = insertDirection();
        jdbc.update("INSERT INTO kb_doc (id, user_id, direction_id, file_hash, name, original_filename, "
                + "file_size, content_type, storage_key, status, analyzer_version) "
                + "VALUES (?, ?, ?, ?, ?, ?, 1024, 'text/markdown', ?, 'READY', ?)",
            docId, userId, directionId, "hash-" + docId, "smoke.md", "smoke.md",
            "knowledge/smoke/" + docId, "chunker-smoke-v1");
        return docId;
    }

    private UUID insertDirection() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO direction (id, key, name, origin) VALUES (?, ?, ?, 'SKILL_BUILTIN')",
            id, "smoke-" + UUID.randomUUID().toString().substring(0, 8), "SQL 烟测方向");
        return id;
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, email, password_hash, status, role) "
                + "VALUES (?, ?, ?, 'ACTIVE', 'USER')",
            id, "smoke-" + UUID.randomUUID().toString().substring(0, 8) + "@annona.local",
            "smoke-no-login");
        return id;
    }

    private record Chunk(UUID id) {
    }
}
