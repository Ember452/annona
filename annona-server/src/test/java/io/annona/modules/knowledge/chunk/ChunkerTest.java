package io.annona.modules.knowledge.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.common.parse.DocumentBlock;
import io.annona.common.parse.DocumentBlock.BlockType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Chunker 纯函数单测。splitText 组逐条移植 🅢 summer-checkin tests/rag-chunk.test.ts
 * （上游行为规格，含两条死循环兜底验收）；chunk 组是 IR 形态的新增规格
 * （标题路径栈、偏移正确性、表格/列表合并——上游没有）。
 */
@DisplayName("Chunker：结构感知分块")
class ChunkerTest {

    private static DocumentBlock heading(int level, String text, int start, int end) {
        return new DocumentBlock(BlockType.HEADING, level, text, start, end);
    }

    private static DocumentBlock para(String text, int start, int end) {
        return new DocumentBlock(BlockType.PARAGRAPH, null, text, start, end);
    }

    @Nested
    @DisplayName("splitText：🅢 行为规格移植")
    class SplitText {

        @Test
        @DisplayName("空文本与纯空白 → 空数组")
        void blankReturnsEmpty() {
            assertThat(Chunker.splitText("", 500, 50)).isEmpty();
            assertThat(Chunker.splitText("   \n\n  ", 500, 50)).isEmpty();
        }

        @Test
        @DisplayName("短段落整段作为一个分片")
        void shortParagraphStaysWhole() {
            assertThat(Chunker.splitText("你好，世界。", 500, 50)).containsExactly("你好，世界。");
        }

        @Test
        @DisplayName("多段落各自成片，且不产生空片（1/2/3 个换行分隔均成立）")
        void paragraphsSplitIndependently() {
            List<String> pieces = Chunker.splitText("第一段\n\n第二段\n\n\n第三段", 500, 50);

            assertThat(pieces).containsExactly("第一段", "第二段", "第三段");
        }

        @Test
        @DisplayName("长段落切成多片，每片不超过 chunkSize")
        void longParagraphSplitWithinLimit() {
            String text = "句子。".repeat(400);

            List<String> pieces = Chunker.splitText(text, 500, 50);

            assertThat(pieces).isNotEmpty();
            assertThat(pieces).allSatisfy(piece -> assertThat(piece.length()).isLessThanOrEqualTo(500));
        }

        @Test
        @DisplayName("优先在句末断开，而不是硬切")
        void prefersSentenceBreak() {
            String text = "句子。".repeat(400);

            assertThat(Chunker.splitText(text, 500, 50).get(0)).endsWith("。");
        }

        @Test
        @DisplayName("无任何断句符的超长文本仍终止，不退化成逐字推进（死循环兜底②③）")
        void noSeparatorStillTerminates() {
            List<String> pieces = Chunker.splitText("a".repeat(3000), 500, 50);

            assertThat(pieces.size()).isGreaterThan(0);
            assertThat(pieces.size()).isLessThan(20);
        }

        @Test
        @DisplayName("overlap 生效：无断句符 1200 字 → 3 片且首片恰 500 字")
        void overlapAdvancesWindow() {
            List<String> pieces = Chunker.splitText("x".repeat(1200), 500, 50);

            assertThat(pieces).hasSize(3);
            assertThat(pieces.get(0)).hasSize(500);
        }
    }

    @Nested
    @DisplayName("chunk：结构化块 IR 形态")
    class ChunkIr {

        @Test
        @DisplayName("空块列表与全空白块 → 空结果")
        void emptyInputReturnsEmpty() {
            assertThat(Chunker.chunk(List.of(), ChunkOptions.DEFAULTS)).isEmpty();
            assertThat(Chunker.chunk(List.of(para("   ", 0, 3)), ChunkOptions.DEFAULTS)).isEmpty();
        }

        @Test
        @DisplayName("单段落无标题 → 单片，headingPath 空串，偏移精确映射到原文")
        void singleBlockOffsetsMapped() {
            List<KnowledgeChunk> chunks =
                Chunker.chunk(List.of(para("你好，世界。", 10, 16)), ChunkOptions.DEFAULTS);

            assertThat(chunks).hasSize(1);
            KnowledgeChunk chunk = chunks.get(0);
            assertThat(chunk.index()).isZero();
            assertThat(chunk.headingPath()).isEmpty();
            assertThat(chunk.charStart()).isEqualTo(10);
            assertThat(chunk.charEnd()).isEqualTo(16);
            assertThat(chunk.text()).isEqualTo("你好，世界。");
        }

        @Test
        @DisplayName("多级标题维护路径栈：每片 headingPath 反映祖先链，文本带路径前缀")
        void headingPathStackFollowsOutline() {
            List<KnowledgeChunk> chunks = Chunker.chunk(List.of(
                heading(1, "Java", 0, 4), para("第一节内容", 4, 9),
                heading(2, "基础", 9, 11), para("第二节内容", 11, 16),
                heading(3, "语法", 16, 18), para("第三节内容", 18, 23),
                heading(2, "进阶", 23, 25), para("第四节内容", 25, 30)
            ), ChunkOptions.DEFAULTS);

            assertThat(chunks).extracting(KnowledgeChunk::headingPath).containsExactly(
                "Java", "Java > 基础", "Java > 基础 > 语法", "Java > 进阶");
            assertThat(chunks).extracting(KnowledgeChunk::index)
                .containsExactly(0, 1, 2, 3);
            assertThat(chunks.get(2).text()).startsWith("Java > 基础 > 语法\n第三节内容");
        }

        @Test
        @DisplayName("第一个标题之前的内容保留为一片，headingPath 为空")
        void preambleBeforeFirstHeadingKept() {
            List<KnowledgeChunk> chunks = Chunker.chunk(List.of(
                para("前言导语", 0, 4),
                heading(1, "标题", 4, 6), para("正文", 6, 8)
            ), ChunkOptions.DEFAULTS);

            assertThat(chunks).hasSize(2);
            assertThat(chunks.get(0).headingPath()).isEmpty();
            assertThat(chunks.get(0).text()).isEqualTo("前言导语");
            assertThat(chunks.get(0).charStart()).isZero();
        }

        @Test
        @DisplayName("空标题节（标题后无正文）不产生分块，路径继续下传")
        void emptySectionEmitsNothing() {
            List<KnowledgeChunk> chunks = Chunker.chunk(List.of(
                heading(1, "甲", 0, 1),
                heading(2, "乙", 1, 2), para("唯一正文", 2, 6)
            ), ChunkOptions.DEFAULTS);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).headingPath()).isEqualTo("甲 > 乙");
        }

        @Test
        @DisplayName("超长节窗口化：每片正文 ≤ 上限且都带同一路径前缀，偏移落在节范围内")
        void longSectionWindowed() {
            String longBody = "这是一句话。".repeat(150);
            List<KnowledgeChunk> chunks = Chunker.chunk(List.of(
                heading(1, "长节", 0, 2), para(longBody, 2, 2 + longBody.length())
            ), ChunkOptions.DEFAULTS);

            assertThat(chunks.size()).isGreaterThan(1);
            String prefix = "长节\n";
            assertThat(chunks).allSatisfy(chunk -> {
                assertThat(chunk.headingPath()).isEqualTo("长节");
                assertThat(chunk.text()).startsWith(prefix);
                assertThat(chunk.text().length() - prefix.length()).isLessThanOrEqualTo(800);
                assertThat(chunk.charStart()).isGreaterThanOrEqualTo(2);
                assertThat(chunk.charEnd()).isLessThanOrEqualTo(2 + longBody.length());
            });
        }

        @Test
        @DisplayName("表格与列表块并入节正文参与合并（不再逐块碎片化）")
        void tableAndListCoalesceIntoSection() {
            List<KnowledgeChunk> chunks = Chunker.chunk(List.of(
                heading(1, "指标", 0, 2),
                new DocumentBlock(BlockType.TABLE, null, "召回率|0.82", 2, 10),
                new DocumentBlock(BlockType.LIST_ITEM, null, "按方向聚合", 10, 15),
                para("以上为实验结论。", 15, 22)
            ), ChunkOptions.DEFAULTS);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).text())
                .contains("指标\n")
                .contains("召回率|0.82")
                .contains("按方向聚合")
                .contains("以上为实验结论。");
        }

        @Test
        @DisplayName("偏移映射跨块正确：片首尾钳到所属块的原文偏移")
        void offsetsSpanMultipleBlocks() {
            List<KnowledgeChunk> chunks = Chunker.chunk(List.of(
                para("甲块内容", 100, 104), para("乙块内容", 200, 204)
            ), ChunkOptions.DEFAULTS);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).charStart()).isEqualTo(100);
            assertThat(chunks.get(0).charEnd()).isEqualTo(204);
            assertThat(chunks.get(0).text()).isEqualTo("甲块内容\n\n乙块内容");
        }
    }

    @Nested
    @DisplayName("ChunkOptions：参数校验")
    class OptionsValidation {

        @Test
        @DisplayName("overlap ≥ sectionChunkSize 拒绝（会造成零前进窗口）")
        void overlapMustBeSmallerThanSize() {
            assertThatThrownBy(() -> new ChunkOptions(800, 800, 0.6))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("断点比例越界拒绝")
        void ratioMustBeOpenInterval() {
            assertThatThrownBy(() -> new ChunkOptions(800, 50, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ChunkOptions(800, 50, 1.0)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
