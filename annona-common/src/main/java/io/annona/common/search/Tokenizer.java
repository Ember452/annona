package io.annona.common.search;

/**
 * 中文分词端口（retrieval-hybrid-adr §决策 10；结构文档 §3 把"分词"列为 infrastructure 职责，
 * 端口先行例是 {@code DocumentParser} / {@code ObjectStorage}）。
 *
 * <p><b>不变量：入库写 tokens 与查询切 query 必须用同一个 bean</b>（同一个词典）。两侧用了不同
 * 实现或不同词典时，"查询切出来的词"与"库里存的词"对不上，关键词通道会静默退化成只剩
 * {@code pg_trgm} 兜底——不报错，只是召回变差，评测里才看得见。
 *
 * <p>输出形状被 V5 的生成列表达式 {@code to_tsvector('simple', tokens)} 冻结：token 之间以空白
 * 分隔，且不得含 tsquery 操作符字符（见 {@link #toTsQueryString}）。换分词器等于改 DDL，
 * 所以本端口不是对外扩展点，第三方无法只换一个 bean 就替换它（AGENTS.md §4 判据）。
 */
public interface Tokenizer {

    /**
     * 分词器与词典的版本标识，落 {@code kb_doc_chunk.tokenizer_version}。
     *
     * <p>与 {@code kb_doc.analyzer_version}（分块算法版本）是两个概念：换本值只需重写
     * {@code tokens}，换 analyzer_version 要重新解析与重切正文。
     */
    String version();

    /**
     * 把正文切成可入库的 token 串。
     *
     * @param text 分块正文；{@code null} 与空白按"无 token"处理
     * @return 空白分隔的 token 串；没有可用 token 时返回<b>空串</b>而非 {@code null}
     *         （空串经 {@code to_tsvector} 得到空 tsvector，该行不参与关键词通道）
     */
    String tokenize(String text);

    /**
     * 把查询切成可直接喂给 {@code to_tsquery('simple', ?)} 的串，token 之间用 {@code |}（OR）连接。
     *
     * <p><b>为什么 OR 不用 AND</b>：备考查询多是"Redisson 看门狗 续期"这类词袋，漏切一个词
     * （或用户多打一个字）就会让 AND 整条空手；本通道的职责是把语义通道漏掉的精确词捞回来，
     * 所以召回优先，排序交给 {@code ts_rank} 与 RRF。否决 AND：宁可用一个恒不命中的查询
     * 换零噪声，不符合关键词通道存在的理由。
     *
     * @return OR 串；无可用 token 时返回<b>空串</b>——调用方必须据此跳过关键词通道，
     *         把空串喂给 {@code to_tsquery} 会抛语法错
     */
    String toTsQueryString(String query);
}
