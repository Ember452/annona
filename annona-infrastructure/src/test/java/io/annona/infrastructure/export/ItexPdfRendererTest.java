package io.annona.infrastructure.export;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.annona.common.export.PdfReport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** iText 渲染器纯 JVM 可本机验（不需 Docker/网络）：含中文也产合法 PDF。 */
@DisplayName("ItexPdfRenderer：中文报告渲染为合法 PDF 字节")
class ItexPdfRendererTest {

    private final ItexPdfRenderer renderer = new ItexPdfRenderer();

    @Test
    @DisplayName("含中文的报告 → 以 %PDF 头开头、非空")
    void rendersCjkReportToPdfBytes() {
        PdfReport report = new PdfReport(
            "面试评估报告", "评估器 v2 · 生成 2026-09-30 · 模型 glm-4.7", 88,
            List.of("整体：结构化表达好，例子略少", "亮点：概念准确", "改进：多举生产案例"),
            List.of(new PdfReport.QuestionBlock("题目 1 — 主问题", "88 分", "回答准确，覆盖关键点",
                List.of("概念清晰"), List.of("缺一个边界例子"), false)),
            List.of("综合分按题目难度加权（难度1..5 → 权重1.0..1.5）", "降级题不计入综合分"));

        byte[] pdf = renderer.render(report);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, US_ASCII)).isEqualTo("%PDF-");
        // EOF 标记存在 → 文档正常收尾（document.close 生效），而非半截字节流
        assertThat(new String(pdf, pdf.length - 6, 6, US_ASCII)).contains("%%EOF");
    }

    @Test
    @DisplayName("无综合分（未出分/全降级）也渲染成功，不抛")
    void rendersWithoutCompositeScore() {
        byte[] pdf = renderer.render(new PdfReport("报告", "meta", null,
            List.of(), List.of(), List.of()));
        assertThat(new String(pdf, 0, 5, US_ASCII)).isEqualTo("%PDF-");
    }
}
