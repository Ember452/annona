package io.annona.spi.fake;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FakeEmbeddingProvider 确定性契约：同文本同向量、异文本异向量、单位长度、维度可配。
 * spi 模块刻意不带 assertj（对外发布契约 jar，测试依赖最简），断言用 JUnit 原生。
 */
@DisplayName("FakeEmbeddingProvider：确定性向量")
class FakeEmbeddingProviderTest {

    @Test
    @DisplayName("同文本两次调用得到完全相同的向量")
    void deterministicForSameText() {
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider(8);

        float[] first = provider.embed(List.of("一段文本")).vectors().get(0);
        float[] second = provider.embed(List.of("一段文本")).vectors().get(0);

        assertArrayEquals(first, second);
    }

    @Test
    @DisplayName("不同文本得到不同向量；向量归一化到单位长度")
    void distinctTextsProduceDistinctUnitVectors() {
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider(8);

        float[] a = provider.embed(List.of("文本甲")).vectors().get(0);
        float[] b = provider.embed(List.of("文本乙")).vectors().get(0);

        assertNotEquals(a, b);
        double normA = 0;
        for (float v : a) {
            normA += v * v;
        }
        assertEquals(1.0, normA, 1e-5);
    }

    @Test
    @DisplayName("默认维度 1024 与 V4 向量列 DDL 对齐；非正维度拒绝")
    void defaultDimensionsMatchDdl() {
        assertEquals(1024, new FakeEmbeddingProvider().dimensions());
        assertEquals(8, new FakeEmbeddingProvider(8).dimensions());
        assertThrows(IllegalArgumentException.class, () -> new FakeEmbeddingProvider(0));
    }

    @Test
    @DisplayName("确定性假 usage：非零、只含输入侧、同输入同值（计量链可验非零的根基）")
    void deterministicPromptUsage() {
        var first = provider8().embed(List.of("一段文本")).usage();
        var second = provider8().embed(List.of("一段文本")).usage();

        assertTrue(first.promptTokens() > 0, "fake 按 chars/4 估输入 token，非零供计量链断言");
        assertEquals(0, first.completionTokens(), "embedding 无输出口");
        assertEquals(first.promptTokens(), second.promptTokens());
    }

    private static FakeEmbeddingProvider provider8() {
        return new FakeEmbeddingProvider(8);
    }
}
