package io.annona.infrastructure.parse;

import java.util.regex.Pattern;

/**
 * 文本清洗规则集——逐条移植 🅖 TextCleaningService（语义层：控制字符/图片行/URL/分隔线；
 * 格式层：换行归一、行尾空白、连续空行压缩）。上游对整篇文本清洗，annona 改为<b>按块</b>
 * 清洗（解析器逐块调用）——规则都是行级/字符级的，逐块应用结果等价，且保证块偏移
 * 与清洗后文本一致（IR 偏移口径，DocumentBlock 注释）。
 */
public final class TextCleaner {

    /** 控制字符（保留 \n \t——换行是块内结构，制表符是表格语义）。 */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]");

    /** PDF 解析常产出的图片占位整行：image1.png 等。 */
    private static final Pattern IMAGE_FILE_LINE =
        Pattern.compile("(?m)^image\\d+\\.(?:png|jpe?g|gif|bmp|webp)\\s*$");

    /** 图片 URL（含查询串）；正文里讨论 URL 的场景交给引用链接而非清洗规则。 */
    private static final Pattern IMAGE_URL =
        Pattern.compile("https?://\\S+?\\.(?:png|jpe?g|gif|bmp|webp)(\\?\\S*)?");

    /** file:// 协议路径（文档内部链接，无检索价值）。 */
    private static final Pattern FILE_URL = Pattern.compile("file:(//)?\\S+");

    /** 纯分隔线整行（--- *** ___ 等 markdown/PDF 噪声）。 */
    private static final Pattern SEPARATOR_LINE = Pattern.compile("(?m)^\\s*[-_*=]{3,}\\s*$");

    private TextCleaner() {
    }

    /** 块文本清洗：去噪 + 换行归一 + 行尾空白 + 空行压缩 + 首尾裁剪。 */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = CONTROL_CHARS.matcher(text).replaceAll("");
        text = IMAGE_FILE_LINE.matcher(text).replaceAll("");
        text = IMAGE_URL.matcher(text).replaceAll("");
        text = FILE_URL.matcher(text).replaceAll("");
        text = SEPARATOR_LINE.matcher(text).replaceAll("");
        text = text.replaceAll("(?m)[ \t]+$", "");
        text = text.replaceAll("\n{3,}", "\n\n");
        return text.strip();
    }
}
