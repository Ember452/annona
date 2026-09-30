package io.annona.infrastructure.export;

import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import io.annona.common.export.PdfReport;
import io.annona.common.export.ReportPdfRenderer;
import java.io.ByteArrayOutputStream;
import org.springframework.stereotype.Component;

/**
 * iText 8 报告 PDF 渲染器（{@link ReportPdfRenderer} 的 infrastructure 实现，P1b-09）。
 *
 * <p>中文不乱码靠 <b>font-asian 的内置 Adobe CJK 字体</b> {@code STSong-Light} + {@code UniGB-UCS2-H}
 * 编码（非嵌入：字体度量随 PDF 阅读器，PDF 不内嵌字形、无第三方字体二进制入仓与许可问题，见
 * pdf-export-itext-adr 决策）。iText 为 AGPL，与本项 AGPL-3.0 许可兼容。
 */
@Component
public class ItexPdfRenderer implements ReportPdfRenderer {

    /** Adobe-GB1 标准 CJK 字体名与统一汉字编码（font-asian 提供 CMap/度量，无需嵌入字形）。 */
    private static final String CJK_FONT = "STSong-Light";
    private static final String CJK_ENCODING = "UniGB-UCS2-H";

    @Override
    public byte[] render(PdfReport report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfFont font = PdfFontFactory.createFont(CJK_FONT, CJK_ENCODING,
                PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED);
            PdfDocument pdf = new PdfDocument(new PdfWriter(out));
            Document document = new Document(pdf);
            document.setFont(font).setFontSize(10.5f);

            document.add(new Paragraph(report.title()).setFontSize(18f).setBold().setMarginBottom(4));
            if (report.meta() != null && !report.meta().isBlank()) {
                document.add(new Paragraph(report.meta()).setFontColor(
                    com.itextpdf.kernel.colors.ColorConstants.DARK_GRAY).setMarginBottom(10));
            }
            if (report.compositeScore() != null) {
                document.add(new Paragraph("综合分：" + report.compositeScore() + " / 100")
                    .setFontSize(13f).setBold().setMarginBottom(10));
            }
            for (String line : report.overview()) {
                document.add(bullet(line));
            }
            if (!report.questions().isEmpty()) {
                document.add(new Paragraph("逐题评估").setFontSize(13f).setBold().setMarginTop(10));
                for (PdfReport.QuestionBlock q : report.questions()) {
                    document.add(new Paragraph(q.heading() + "  —  " + q.scoreLabel()).setBold());
                    if (q.feedback() != null && !q.feedback().isBlank()) {
                        document.add(new Paragraph(q.feedback()));
                    }
                    for (String s : q.strengths()) {
                        document.add(indented("＋ " + s));
                    }
                    for (String s : q.improvements()) {
                        document.add(indented("－ " + s));
                    }
                }
            }
            if (!report.rationale().isEmpty()) {
                document.add(new Paragraph("决策理由").setFontSize(13f).setBold().setMarginTop(10));
                for (String line : report.rationale()) {
                    document.add(bullet(line));
                }
            }
            document.close();
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new io.annona.common.exception.BusinessException(
                io.annona.common.exception.ErrorCode.INTERNAL_ERROR, "PDF 渲染失败");
        }
    }

    private static Paragraph bullet(String text) {
        return new Paragraph("• " + text).setMarginBottom(2);
    }

    private static Paragraph indented(String text) {
        return new Paragraph(text).setMarginLeft(16).setMarginBottom(1)
            .setFontColor(com.itextpdf.kernel.colors.ColorConstants.GRAY);
    }
}
