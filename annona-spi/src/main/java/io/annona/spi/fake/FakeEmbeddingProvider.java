package io.annona.spi.fake;

import io.annona.spi.model.EmbeddingProvider;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link EmbeddingProvider} 的确定性实现：不真发 HTTP，按文本哈希生成稳定向量，
 * 让入库管线在无 Key 环境（本机单测 / CI 集测）完整跑通状态机、分块落库与进度推进。
 *
 * <p>同文本永远得到同向量；不同文本几乎必然不同向量（64 位种子 + LCG），足以支撑
 * 检索链路的"命中 != 命中其他块"断言。默认维度 1024 与 V4 DDL {@code vector(1024)}
 * 对齐，docker 组集测可直接对真库跑全管线；小维度供纯逻辑测试压缩断言成本。
 */
public final class FakeEmbeddingProvider implements EmbeddingProvider {

    /** 与 {@code annona.model.embedding.provider} 的取值 {@code fake} 对齐。 */
    public static final String NAME = "fake";

    private static final int DEFAULT_DIMENSIONS = 1024;

    private final int dimensions;

    public FakeEmbeddingProvider() {
        this(DEFAULT_DIMENSIONS);
    }

    public FakeEmbeddingProvider(int dimensions) {
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions 必须为正，实际 " + dimensions);
        }
        this.dimensions = dimensions;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(vectorFor(text == null ? "" : text, dimensions));
        }
        return out;
    }

    /** 以文本哈希为种子的 LCG，输出归一化到单位长度（点积即余弦相似度，检索语义成立）。 */
    private static float[] vectorFor(String text, int dimensions) {
        long seed = 1125899906842597L;
        for (int i = 0; i < text.length(); i++) {
            seed = 31 * seed + text.charAt(i);
        }
        float[] vector = new float[dimensions];
        float norm = 0f;
        for (int i = 0; i < dimensions; i++) {
            seed = 6364136223846793005L * seed + 1442695040888963407L;
            float value = (int) (seed >>> 33) / (float) Integer.MAX_VALUE;
            vector[i] = value;
            norm += value * value;
        }
        norm = (float) Math.sqrt(norm);
        if (norm == 0f) {
            norm = 1f;
        }
        for (int i = 0; i < dimensions; i++) {
            vector[i] /= norm;
        }
        return vector;
    }
}
