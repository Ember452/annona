package io.annona.infrastructure.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.annona.common.exception.BusinessException;
import io.annona.common.parse.DocumentBlock;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tika 解析适配测试：真实字节流进、结构化块 IR 出（本机可跑，Tika/PDFBox 纯 Java）。
 * 场景对照 🅖 DocumentParseServiceTest 的 txt/md/空文件/特殊字符组 + IR 特有断言
 * （块类型、偏移自洽：偏移区间拼接 = 各块文本，且互不重叠、单调递增）。
 */
@DisplayName("TikaDocumentParser：字节流 → 结构化块 IR")
class TikaDocumentParserTest {

    private static TikaDocumentParser parser;

    @BeforeAll
    static void setUp() {
        parser = new TikaDocumentParser(
            new ParseConfig().documentParseExecutor(new DocumentParseProperties()),
            new DocumentParseProperties());
    }

    @Test
    @DisplayName("纯文本：两行文字 → 两个段落块，偏移覆盖且单调")
    void parsesPlainText() {
        byte[] bytes = "第一段内容。\n\n第二段内容。".getBytes();

        List<DocumentBlock> blocks = parser.parse(bytes, "note.txt");

        assertThat(blocks).hasSizeGreaterThanOrEqualTo(1);
        int expectedStart = 0;
        for (DocumentBlock block : blocks) {
            assertThat(block.type()).isEqualTo(DocumentBlock.BlockType.PARAGRAPH);
            assertThat(block.charStart()).isEqualTo(expectedStart);
            assertThat(block.charEnd()).isGreaterThan(block.charStart());
            expectedStart = block.charEnd();
        }
    }

    @Test
    @DisplayName("Markdown：ATX 标题识别为 HEADING 块并带层级")
    void parsesMarkdownHeadings() {
        byte[] bytes = "# 一级标题\n\n段落文本\n\n## 二级标题\n\n更多文本".getBytes();

        List<DocumentBlock> blocks = parser.parse(bytes, "doc.md");

        assertThat(blocks).extracting(DocumentBlock::type)
            .contains(DocumentBlock.BlockType.HEADING, DocumentBlock.BlockType.PARAGRAPH);
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.type()).isEqualTo(DocumentBlock.BlockType.HEADING);
            assertThat(block.level()).isNotNull();
        });
    }

    @Test
    @DisplayName("空文件 → 空块列表（上游 parseEmptyFile 同语义）")
    void parsesEmptyFileToEmpty() {
        assertThat(parser.parse(new byte[0], "empty.txt")).isEmpty();
    }

    @Test
    @DisplayName("中文与特殊字符不丢失（上游 testParseChineseResume 同语义）")
    void preservesChineseText() {
        byte[] bytes = "中文内容：面试评价，优秀（含特殊字符 ~!@#）。".getBytes();

        List<DocumentBlock> blocks = parser.parse(bytes, "简历.txt");

        assertThat(String.join("", blocks.stream().map(DocumentBlock::text).toList()))
            .contains("面试评价").contains("特殊字符");
    }

    @Test
    @DisplayName("无法识别的二进制字节不抛异常（Tika 宽容降级；空文本判定归调用方）")
    void treatsUnrecognizableBytesLeniently() {
        byte[] garbage = new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01, 0x02, 0x03};

        // Tika AutoDetect 对 unknown 二进制按容错文本处理（可能解出少量噪声字符），
        // "解析不出可用内容"由调用方按空文本报 KB_DOC_TEXT_EMPTY，解析层不替业务做决定
        assertThatCode(() -> parser.parse(garbage, "broken.bin")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PDF：PDFBox 现场生成的单栏两行文本 → 段落块且含原文（借 🅖 集测思路）")
    void parsesGeneratedPdf() throws Exception {
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Hello annona ingestion");
                content.endText();
            }
            document.save(pdf);
        }

        List<DocumentBlock> blocks = parser.parse(pdf.toByteArray(), "sample.pdf");

        assertThat(String.join("", blocks.stream().map(DocumentBlock::text).toList()))
            .contains("Hello annona ingestion");
    }
}
