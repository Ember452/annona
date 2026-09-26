package io.annona.spi.dto;

import java.util.List;
import java.util.Objects;

/**
 * 检索请求。P0-04 只落最小字段集，向量维度、filter DSL 等在 P1a-07 实现时扩展。
 *
 * @param text     查询文本（改写后的最终形态）
 * @param topK     期望返回的命中数上限；必须 &gt; 0，否则宁可直接返回空集也不扫全库
 * @param userId   发起方；用于按用户隔离知识库可见性
 * @param kbDocIds 可选：将检索限定在指定文档集合（{@code null} 或空表示全域；
 *                 构造时归一为不可变空列表，避免实现方各自判 null）
 */
public record RetrievalQuery(String text, int topK, String userId, List<String> kbDocIds) {

    /**
     * 紧凑构造器：归一化 + 防御性拷贝。
     *
     * <p>本类型属于对外发布的契约 jar，实现方（pgvector / ES / 第三方）会直接拿这些字段
     * 拼查询；若允许调用方事后改掉列表，“同一个 query 对象两次检索结果不同”这种
     * 无法复现的问题会直接落在检索指标上。
     */
    public RetrievalQuery {
        text = Objects.requireNonNull(text, "text 不得为 null");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须 > 0，得到 " + topK
                + "；要空结果请直接不要调用检索");
        }
        kbDocIds = kbDocIds == null ? List.of() : List.copyOf(kbDocIds);
    }

    /** 全域检索的便捷构造（不限定文档集合）。 */
    public static RetrievalQuery global(String text, int topK, String userId) {
        return new RetrievalQuery(text, topK, userId, List.of());
    }
}
