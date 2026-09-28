package io.annona.common.search;

/**
 * pgvector 的向量字面量序列化：{@code [v1,v2,...]}，配 SQL 里的 {@code ?::vector} 使用。
 *
 * <p>为什么在 common 而不是 infrastructure：写入方（knowledge 入库）与读取方（retrieval
 * 语义通道）都是业务模块，两边必须产出<b>完全相同</b>的字面量格式——一边写得进、另一边
 * 读不出，表现为"库里明明有向量却零命中"。
 *
 * <p>JPA 没有 vector 类型，所以向量列永不映射进实体（V4/V5 的列注释），一律原生 SQL + 本工具。
 */
public final class VectorLiterals {

    private VectorLiterals() {
    }

    /** 用 float 的原样十进制表示拼接；精度取舍沿用批 1 的写入口径，改动会直接影响余弦距离。 */
    public static String of(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }
}
