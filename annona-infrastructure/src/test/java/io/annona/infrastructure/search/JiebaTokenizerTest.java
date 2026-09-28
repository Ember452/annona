package io.annona.infrastructure.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.search.Tokenizer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 分词器单测 + golden 快照（AGENTS.md §4 把"infrastructure 的分词"点名为 85% 关键纯逻辑包）。
 *
 * <p>快照约定沿用仓库首个先例（{@code knowledge/chunk}）：期望值存
 * {@code src/test/resources/golden/tokenizer/tokens.json}，改词典、改切词模式或改白名单
 * 都会让它变红——必须在评审里逐字段确认 diff 后显式重生成，而不是顺手改期望值。
 * 这条快照的实际意义是：jieba 的输出粒度决定库里存哪些词，而库里存词的粒度一旦变化，
 * 检索召回就变了，而代码一行没动。
 */
@DisplayName("JiebaTokenizer 切词与 tsquery 串（golden 快照）")
class JiebaTokenizerTest {

    private final Tokenizer tokenizer = new JiebaTokenizer();
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"chinese-sentence", "mixed-symbol", "operator-noise", "punctuation-only"})
    @DisplayName("golden 输入的分词结果与快照逐字段一致")
    void matchesGolden(String name) throws Exception {
        JsonNode golden = mapper.readTree(
            getClass().getResourceAsStream("/golden/tokenizer/tokens.json"));
        assertThat(golden.get("version").asText())
            .as("快照登记的版本必须与实现版本一致，否则快照对不上被测代码")
            .isEqualTo(tokenizer.version());

        JsonNode testCase = findCase(name);
        assertThat(tokenizer.tokenize(testCase.get("text").asText()))
            .as("case=%s 的入库 token 串", name)
            .isEqualTo(testCase.get("tokens").asText());
        assertThat(tokenizer.toTsQueryString(testCase.get("text").asText()))
            .as("case=%s 的查询 tsquery 串", name)
            .isEqualTo(testCase.get("tsquery").asText());
    }

    @Test
    @DisplayName("token 只由汉字/字母/数字/下划线组成，查询串里的 | 只可能是连接符")
    void tokensContainNoTsqueryOperatorChars() throws Exception {
        for (String name : List.of("chinese-sentence", "mixed-symbol", "operator-noise")) {
            String text = findCase(name).get("text").asText();

            assertThat(tokensOf(tokenizer.tokenize(text)))
                .as("case=%s 的入库 token", name)
                .allMatch(JiebaTokenizerTest::isPlainLexeme);
            // 去掉连接符后剩下的也必须是纯 lexeme：否则 to_tsquery 会抛语法错
            // （而不是只检查"不含操作符字符"——那会把合法的 | 连接符也报成违规）
            assertThat(tokensOf(tokenizer.toTsQueryString(text).replace(" | ", " ")))
                .as("case=%s 的查询 token", name)
                .allMatch(JiebaTokenizerTest::isPlainLexeme);
        }
    }

    @Test
    @DisplayName("空白与 null 都得到空串（不是 null），调用据此跳过关键词通道")
    void blankYieldsEmptyString() {
        assertThat(tokenizer.tokenize(null)).isEmpty();
        assertThat(tokenizer.tokenize("   \n\t ")).isEmpty();
        assertThat(tokenizer.toTsQueryString(null)).isEmpty();
        assertThat(tokenizer.toTsQueryString("。。。！！！"))
            .as("全标点查询喂给 to_tsquery 会抛语法错，必须在应用侧变成空串")
            .isEmpty();
    }

    @Test
    @DisplayName("并发调用同一 bean 的结果与单线程一致（ThreadLocal 隔离的前提）")
    void concurrentCallsAreConsistent() throws Exception {
        String text = findCase("chinese-sentence").get("text").asText();
        String single = tokenizer.tokenize(text);

        // 不用 Executors.newFixedThreadPool（AGENTS.md §Never Do 与 ArchUnit 规则 7），
        // 测试里也照仓内口径显式建 ThreadPoolExecutor
        ExecutorService pool = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>());
        try {
            Callable<String> call = () -> {
                String once = tokenizer.tokenize(text);
                for (int i = 0; i < 50; i++) {
                    if (!once.equals(tokenizer.tokenize(text))) {
                        return "UNSTABLE";
                    }
                }
                return once;
            };
            List<Future<String>> futures = pool.invokeAll(Collections.nCopies(16, call));
            for (Future<String> future : futures) {
                assertThat(future.get()).isEqualTo(single);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("version() 落在 VARCHAR(32) 内")
    void versionFitsColumn() {
        assertThat(tokenizer.version()).hasSizeLessThanOrEqualTo(32);
    }

    private JsonNode findCase(String name) throws Exception {
        JsonNode root = mapper.readTree(
            getClass().getResourceAsStream("/golden/tokenizer/tokens.json"));
        for (JsonNode node : root.get("cases")) {
            if (name.equals(node.get("name").asText())) {
                return node;
            }
        }
        throw new AssertionError("golden 文件里没有 case: " + name);
    }

    private static List<String> tokensOf(String joined) {
        return joined.isBlank() ? List.of() : List.of(joined.split(" "));
    }

    private static boolean isPlainLexeme(String token) {
        return token.matches("[\\p{IsHan}\\p{Alnum}_]+");
    }
}
