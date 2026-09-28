package io.annona.infrastructure.search;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import io.annona.common.search.Tokenizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * jieba-analysis 分词实现（端口 {@link Tokenizer}；依赖四问见 retrieval-hybrid-adr §决策 10）。
 *
 * <p>文档侧用 {@code SegMode.INDEX}（官方口径：索引模式会把长词再切出子词，提高被召回的概率），
 * 查询侧用 {@code SegMode.SEARCH}（查询模式按最粗粒度切，避免把一个词切碎后到处匹到噪声）。
 * <b>两侧共用同一个词典与同一个 bean</b>是端口 javadoc 里的不变量，本类只切换粒度不切换词典。
 *
 * <p>线程约束：{@code JiebaSegmenter} 的实例本身轻量（词典在内部按 singleton 初始化），
 * 但上游关于"是否线程安全"只有 issue #65 的二手答复。写侧是并发 Stream 消费者、
 * 读侧是用户请求，共享一个实例不值得赌 → 每线程一个 segmenter。线程数由固定的四类池与
 * servlet 线程池封顶，因此 ThreadLocal 的副本数有界。
 */
@Component
public class JiebaTokenizer implements Tokenizer {

    /** 落 {@code kb_doc_chunk.tokenizer_version}；换词典或换切词模式都要动它。 */
    static final String VERSION = "jieba-1.0.2-v1";

    /**
     * 干净 token 的字符集：汉字、字母、数字、下划线。{@link #SEPARATOR} 是它的取反——两处必须
     * 同源，否则会出现"既不是 token 也不是分隔符"的字符。注意 jieba 输出已将 ASCII 转小写。
     *
     * <p><b>遇到非法字符时切成多段而不是整条丢弃</b>：丢弃写法会把 {@code 3.5}、{@code 4.2.1}
     * 这类带分隔符的版本号整条抹掉（golden 实测：技术讲义里这就是丢字），而版本号恰恰是
     * 关键词通道要捣回来的精确词。切分后 {@code 3.5 → 3 5}，入库侧与查询侧走同一规则，
     * lexeme 位置不会不一致，而且结果串里永远没有 tsquery 操作符字符。
     * <p>代价：{@code don't} 会变成 {@code don} + {@code t} 两个噪声词，{@code C++} 只剩 {@code c}。
     * 什么条件下重新评估：评测的 symbol/clause 桶出现成规模漏召，或引入自定义词库时。
     */
    private static final String CLEAN_CHARS = "\\p{IsHan}\\p{Alnum}_";

    private static final Pattern CLEAN_TOKEN = Pattern.compile("[" + CLEAN_CHARS + "]+");

    /** 非干净字符一律当分隔符（包含 tsquery 的 {@code & | ! ( ) : * '} 与各类中英文标点）。 */
    private static final Pattern SEPARATOR = Pattern.compile("[^" + CLEAN_CHARS + "]+");

    private final ThreadLocal<JiebaSegmenter> segmenters = ThreadLocal.withInitial(JiebaSegmenter::new);

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String tokenize(String text) {
        return String.join(" ", cut(text, JiebaSegmenter.SegMode.INDEX));
    }

    @Override
    public String toTsQueryString(String query) {
        return String.join(" | ", cut(query, JiebaSegmenter.SegMode.SEARCH));
    }

    /**
     * 切词 + 按非词字符拆成干净 token。空白输入与"全被拆空"都返回空列表
     * （调用方据此让该行/该查询不参与关键词通道）。
     */
    private List<String> cut(String text, JiebaSegmenter.SegMode mode) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (SegToken token : segmenters.get().process(text, mode)) {
            // 字段名是 word（javap 实测 1.0.2）；新版本里叫 docToken，升级依赖时要跟着改
            String lexeme = token.word;
            if (lexeme == null) {
                continue;
            }
            for (String part : SEPARATOR.split(lexeme)) {
                if (!part.isEmpty() && CLEAN_TOKEN.matcher(part).matches()) {
                    tokens.add(part);
                }
            }
        }
        return tokens;
    }
}
