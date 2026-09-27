package io.annona.infrastructure.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * TextCleaner 规则单测——场景移植 🅖 TextCleaningServiceTest（语义层逐条 + 格式层逐条）。
 * 规则本身是整篇文本的行级清洗，annona 按块应用（TikaDocumentParser），断言口径一致。
 */
@DisplayName("TextCleaner：块文本清洗规则（借 🅖）")
class TextCleanerTest {

    @Nested
    @DisplayName("语义层")
    class Semantic {

        @Test
        @DisplayName("纯空白内容清洗后为空")
        void blankContent() {
            assertThat(TextCleaner.clean("   ")).isEmpty();
            assertThat(TextCleaner.clean("")).isEmpty();
            assertThat(TextCleaner.clean(null)).isEmpty();
        }

        @Test
        @DisplayName("图片占位整行删除（image1.png 独占一行）")
        void removesImageFilenameLines() {
            String cleaned = TextCleaner.clean("前文\nimage1.png\nimage23.jpeg \n后文");

            assertThat(cleaned).isEqualTo("前文\n\n后文");
        }

        @Test
        @DisplayName("正文里含同名图片串时不误删（非整行不匹配）")
        void preservesImageFilenameInsideText() {
            String cleaned = TextCleaner.clean("这张图叫 image1.png 很有名");

            assertThat(cleaned).contains("image1.png");
        }

        @Test
        @DisplayName("图片 URL 删除")
        void removesImageUrls() {
            String cleaned = TextCleaner.clean("看图 https://x.com/a/b.png?v=2 结束");

            assertThat(cleaned).isEqualTo("看图  结束");
        }

        @Test
        @DisplayName("file:// 路径删除")
        void removesFileUrls() {
            String cleaned = TextCleaner.clean("本地 file:///tmp/a.pdf 引用");

            assertThat(cleaned).isEqualTo("本地  引用");
        }

        @Test
        @DisplayName("纯分隔线整行删除（--- *** ___），留下的连续空行随后被压缩")
        void removesSeparatorLines() {
            String cleaned = TextCleaner.clean("标题\n---\n***\n___\n正文");

            assertThat(cleaned).isEqualTo("标题\n\n正文");
        }

        @Test
        @DisplayName("控制字符删除但保留换行与制表符")
        void removesControlCharsKeepsNewlineTab() {
            String cleaned = TextCleaner.clean("a\u0000b\u0007c\nd\te");

            assertThat(cleaned).isEqualTo("abc\nd\te");
        }
    }

    @Nested
    @DisplayName("格式层")
    class Formatting {

        @Test
        @DisplayName("CRLF 与孤 CR 归一为 LF")
        void normalizesLineEndings() {
            assertThat(TextCleaner.clean("a\r\nb\rc")).isEqualTo("a\nb\nc");
        }

        @Test
        @DisplayName("行尾空白与制表符删除")
        void trimsTrailingWhitespacePerLine() {
            assertThat(TextCleaner.clean("第一行  \n第二行\t\n")).isEqualTo("第一行\n第二行");
        }

        @Test
        @DisplayName("3 个以上连续换行压缩为 2 个")
        void compressesMultipleBlankLines() {
            assertThat(TextCleaner.clean("a\n\n\n\nb")).isEqualTo("a\n\nb");
        }

        @Test
        @DisplayName("首尾裁剪")
        void stripsOuterWhitespace() {
            assertThat(TextCleaner.clean("\n\n 内容 \n\n")).isEqualTo("内容");
        }
    }
}
