package io.annona.infrastructure.parse;

import io.annona.common.parse.DocumentBlock;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 块切分（Tika 3.2.x 不带 markdown 解析器，md 讲义是 annona 核心输入——
 * 标题语义不能降级为纯文本，故自建极简通道；🅢 上游即原生处理 md）。
 *
 * <p>支持的语法面（讲义场景够用，不做完整 md 解析器）：ATX 标题（#{1,6}）、围栏代码块
 * （``` / ~~~，整块保真）、无序/有序列表项、竖线表格行、引用行降级为段落、连续非空行
 * 合并为一个段落。偏移口径与 Tika 通道一致：各块清洗后文本按序拼接（分隔符不计入，
 * DocumentBlock 注释）。
 */
public final class MarkdownBlockParser {

    private MarkdownBlockParser() {
    }

    /** 文件名是否走 markdown 通道。 */
    public static boolean isMarkdown(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".mdown");
    }

    public static List<DocumentBlock> parse(String source) {
        List<DocumentBlock> blocks = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return blocks;
        }
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int offset = 0;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank()) {
                i++;
                continue;
            }
            if (isFence(line)) {
                // 围栏标记从 trim 后的行取：Markdown 允许围栏带 ≤3 空格缩进，
                // 用原始行 substring(0,3) 会让闭合匹配不上、整篇误判成代码块
                String fence = line.trim().startsWith("~~~") ? "~~~" : "```";
                StringBuilder block = new StringBuilder(line.strip());
                i++;
                while (i < lines.length && !lines[i].trim().startsWith(fence)) {
                    block.append('\n').append(lines[i].stripTrailing());
                    i++;
                }
                if (i < lines.length) {
                    block.append('\n').append(lines[i].strip());
                    i++; // 收掉闭合围栏行
                }
                offset = emit(blocks, DocumentBlock.BlockType.CODE, null, block.toString(), offset);
                continue;
            }
            if (line.matches("^#{1,6}[ \\t]+.*")) {
                // 层级 = 行首 # 的个数（indexOf(' ') 对 "#\t标题" 会算出 -1）
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') {
                    level++;
                }
                offset = emit(blocks, DocumentBlock.BlockType.HEADING, level,
                    line.replaceFirst("^#{1,6}[ \\t]+", "").strip(), offset);
                i++;
                continue;
            }
            if (line.matches("^\\s*(?:[-*+]|\\d+[.)])\\s+.*")) {
                offset = emit(blocks, DocumentBlock.BlockType.LIST_ITEM, null, line.strip(), offset);
                i++;
                continue;
            }
            if (line.strip().startsWith("|") && line.strip().endsWith("|")) {
                StringBuilder block = new StringBuilder(line.strip());
                i++;
                while (i < lines.length && lines[i].strip().startsWith("|")) {
                    block.append('\n').append(lines[i].strip());
                    i++;
                }
                offset = emit(blocks, DocumentBlock.BlockType.TABLE, null, block.toString(), offset);
                continue;
            }
            // 引用行与普通文本都归段落；连续非空行合并
            StringBuilder block = new StringBuilder(line.strip());
            i++;
            while (i < lines.length && !lines[i].isBlank()
                && !lines[i].matches("^#{1,6}\\s+.*")
                && !isFence(lines[i])
                && !lines[i].matches("^\\s*(?:[-*+]|\\d+[.)])\\s+.*")
                && !(lines[i].strip().startsWith("|") && lines[i].strip().endsWith("|"))) {
                block.append('\n').append(lines[i].strip());
                i++;
            }
            offset = emit(blocks, DocumentBlock.BlockType.PARAGRAPH, null, block.toString(), offset);
        }
        return blocks;
    }

    private static boolean isFence(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    /** 清洗 + 提交：返回推进后的偏移。 */
    private static int emit(List<DocumentBlock> blocks, DocumentBlock.BlockType type, Integer level,
        String raw, int offset) {
        String cleaned = TextCleaner.clean(raw);
        if (cleaned.isBlank()) {
            return offset;
        }
        blocks.add(new DocumentBlock(type, level, cleaned, offset, offset + cleaned.length()));
        return offset + cleaned.length();
    }
}
