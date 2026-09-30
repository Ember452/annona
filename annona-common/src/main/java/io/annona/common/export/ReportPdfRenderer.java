package io.annona.common.export;

/**
 * PDF 渲染端口（P1b-09）：把中性 {@link PdfReport} 渲染为 PDF 字节。实现方 = iText
 * （{@code infrastructure/export}，SDK 只在 infra，AGENTS §4）；调用方（evaluation 模块）
 * 只见本端口，拿不到 iText 类型。
 *
 * <p>取舍：返回字节而非写存储——调用方决定去向（直接回下载 or 存 ObjectStorage），端口不
 * 绑定 I/O 目标。渲染是纯计算（无 DB/网络），可在调用方事务外安全执行。
 */
public interface ReportPdfRenderer {

    /** @return 完整 PDF 文档字节（含 {@code %PDF} 头）；实现方以运行时业务异常表达渲染失败。 */
    byte[] render(PdfReport report);
}
