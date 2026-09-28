package io.annona.modules.knowledge.chunk;

import io.annona.common.parse.DocumentBlock;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构感知分块纯函数（★P1a-06 关键纯逻辑包，无 Spring 无仓库依赖，可表驱动单测 + golden 快照）。
 * 算法唯一权威定义：docs/specs/2026-09-27-knowledge-ingestion-adr.md §决策 4。
 *
 * <p>两级切分：先按标题层级栈把块 IR 切成节（每块归属其标题路径），节内再做滑窗。
 * 死循环三重兜底（借 🅢 chunk.ts，验收用例逐条移植）：① 窗口 trim 后全空白 → 跳过；
 * ② 窗口到达文本末尾即停（防尾部 ≤overlap 的小尾巴把 advance 推成负数）；
 * ③ advance 至少 1，保证每轮必然前进。
 *
 * <p>ASCII 标识符边界（char-v2）：收口与推进边界不得切开 {@code application.properties}
 * 这类原子串（char-v1 真库实测出现过以 {@code lication.properties} 开头的块）。处理是
 * <b>回退到串起点（pull-back）而非前推</b>——前推会把半截串丢给前片、并让下一片以句末
 * 分隔符开头（2026-09-28 首次修复即因此回退，见 knowledge-ingestion-adr §重新评估）；
 * 回退的代价是标识符在相邻两片各出现一次（chunkId 不同、引用展示由 {@code chunk_index}
 * 消歧，检索无影响）。位移超过 {@link #MAX_ATOMIC_SHIFT} 不迁移；起点回退另有
 * "串起点必须仍在当前窗口之后"的护栏（见 {@link #retreatStart} 注释的残余边界）。
 *
 * <p>与 🅢 的两处刻意偏离（取舍记录）：① 每片文本带完整标题路径前缀行——上游只带
 * 本节标题行，多级标题下丢失祖先上下文；前缀是生成物，{@code charStart/charEnd}
 * 只覆盖正文，引用跳转不漂移；② 节内小块（列表项/表格）合并后再窗口化——上游逐段落
 * 独立成片会把列表打碎成碎片块，合并后单块上限语义不变。
 */
public final class Chunker {

    /** 分块算法版本（落 {@code kb_doc.analyzer_version}）；算法行为变更必须换版本号。 */
    public static final String VERSION = "char-v2";

    /** 句末分隔符（中英全量借 🅢，只判成员与顺序无关）。 */
    private static final String SENTENCE_SEPARATORS = "。？！.?!";

    /** 句末断点最小位置比例（🅢 实测值；调优等 P1a-09 实测数字）。 */
    private static final double SENTENCE_BREAK_RATIO = 0.6;

    /** 边界回退的最大位移（字符）。原子串超过该长度时不迁移边界：超长串（base64、长数字串）
     * 不是标识符，没资格要求原子性；该上限同时防止回退吃掉全部推进量。 */
    private static final int MAX_ATOMIC_SHIFT = 32;

    private Chunker() {
    }

    /**
     * 把块 IR 切成分块序列。{@code index} 全文档连续（0..n-1）；空白块跳过；
     * 空标题节（标题后没有正文）不产生分块。入参 {@code blocks} 为空时返回空列表。
     */
    public static List<KnowledgeChunk> chunk(List<DocumentBlock> blocks, ChunkOptions options) {
        List<KnowledgeChunk> result = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) {
            return result;
        }
        List<String> path = new ArrayList<>();
        List<Segment> body = new ArrayList<>();
        for (DocumentBlock block : blocks) {
            if (block.type() == DocumentBlock.BlockType.HEADING) {
                emitSection(path, body, options, result);
                body = new ArrayList<>();
                int level = block.level() == null ? path.size() + 1 : block.level();
                while (path.size() >= level) {
                    path.remove(path.size() - 1);
                }
                path.add(block.text());
            } else if (!block.text().isBlank()) {
                body.add(new Segment(block.text(), block.charStart(), block.charEnd()));
            }
        }
        emitSection(path, body, options, result);
        return result;
    }

    /**
     * 纯文本滑窗切分（🅢 splitText 的 Java 移植，保留其"段落各自成片、不跨段合并"语义）。
     * 供行为规格移植测试与未来纯文本直传路径使用；IR 管线走 {@link #chunk}。
     */
    public static List<String> splitText(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\n{2,}")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= chunkSize) {
                out.add(trimmed);
                continue;
            }
            for (int[] window : windowRanges(trimmed, chunkSize, overlap, SENTENCE_BREAK_RATIO)) {
                String piece = trimmed.substring(window[0], window[1]).trim();
                if (!piece.isEmpty()) {
                    out.add(piece);
                }
            }
        }
        return out;
    }

    /** 一个正文段：清洗后全文中的一段文本及其偏移。 */
    private record Segment(String text, int globalStart, int globalEnd) {
    }

    private static void emitSection(List<String> path, List<Segment> segments, ChunkOptions options,
        List<KnowledgeChunk> out) {
        if (segments.isEmpty()) {
            return;
        }
        // 节正文 = 段文本按 "\n\n" 拼接；分隔符占局部下标但无偏移映射（映射时钳到最近段边界）
        StringBuilder body = new StringBuilder();
        List<int[]> ranges = new ArrayList<>();
        int local = 0;
        for (Segment segment : segments) {
            if (body.length() > 0) {
                body.append("\n\n");
                local += 2;
            }
            ranges.add(new int[] {local, local + segment.text().length(), segment.globalStart(), segment.globalEnd()});
            body.append(segment.text());
            local += segment.text().length();
        }
        String text = body.toString();
        String joinedPath = String.join(" > ", path);
        String prefix = path.isEmpty() ? "" : joinedPath + "\n";
        int size = options.sectionChunkSize();

        if (text.length() <= size) {
            emitPiece(out, ranges, text, 0, text.length(), joinedPath, prefix);
            return;
        }
        for (int[] window : windowRanges(text, size, options.overlap(), options.sentenceBreakRatio())) {
            emitPiece(out, ranges, text, window[0], window[1], joinedPath, prefix);
        }
    }

    private static void emitPiece(List<KnowledgeChunk> out, List<int[]> ranges, String text,
        int from, int to, String joinedPath, String prefix) {
        int[] trim = trimBounds(text, from, to);
        if (trim == null) {
            return; // 兜底①：窗口全空白（分节体已清洗，正常不触发；防御解析器边界）
        }
        int charStart = mapGlobal(ranges, trim[0]);
        int charEnd = mapGlobal(ranges, trim[1]);
        out.add(new KnowledgeChunk(out.size(), joinedPath, charStart, charEnd,
            prefix + text.substring(trim[0], trim[1])));
    }

    /**
     * 滑窗切分（🅢 splitText 的窗口核心）：窗口到达句末断点则提前收口；
     * 兜底②到尾即停、兜底③ advance 至少 1。收口与推进边界若严格落在 ASCII 原子串内部，
     * 回退到串起点（{@link #retreatEnd} / {@link #retreatStart}）。返回 [start,end) 局部下标对。
     */
    private static List<int[]> windowRanges(String text, int size, int overlap, double ratio) {
        List<int[]> ranges = new ArrayList<>();
        int start = 0;
        int length = text.length();
        while (start < length) {
            int end = Math.min(start + size, length);
            if (end < length) {
                int cut = lastSentenceBreak(text, start, end, size, ratio);
                if (cut > 0) {
                    end = cut;
                }
                end = retreatEnd(text, start, end, size, ratio);
            }
            ranges.add(new int[] {start, end});
            if (end >= length) {
                break; // 兜底②：到尾即停，防尾部 ≤overlap 小尾巴造成 advance≤0
            }
            int advance = (end - start) - overlap;
            if (advance <= 0) {
                advance = 1; // 兜底③：advance 至少 1，保证必然前进
            }
            start = retreatStart(text, start + advance, start);
        }
        return ranges;
    }

    /**
     * 在 [start,end) 内找最靠后的句末分隔符，要求其位置 > start + size×ratio
     *（避免在窗口开头就近断开产生碎片）；找不到返回 -1，窗口按原边界硬切。
     */
    private static int lastSentenceBreak(String text, int start, int end, int size, double ratio) {
        for (int i = end - 1; i >= start; i--) {
            if ((i - start) > size * ratio && SENTENCE_SEPARATORS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }

    /** ASCII 原子字符集：标识符、路径、命令行常见字符（沿用 2026-09-28 边界语义评审的定义）。
     * 中文不在内，中文滑窗语义与 jieba 分词不受影响。包内可见供不变量测试复用同一份字符集。 */
    static boolean isAtomic(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
            || c == '.' || c == '_' || c == '-' || c == '@' || c == '/';
    }

    /**
     * {@code pos} 严格落在原子串内部时返回该串起点，否则 -1。"严格内部"指 pos 与 pos-1
     * 都是原子字符：正常句末断点（如 {@code properties. } 的句点收口，其后是非原子字符）
     * 不会被误判；而句点落在串内（{@code server.properties} 的内部点，其后仍是原子字符）
     * 会被识别为切串，需要回退。
     */
    private static int atomicRunStart(String text, int pos) {
        if (pos <= 0 || pos >= text.length() || !isAtomic(text.charAt(pos)) || !isAtomic(text.charAt(pos - 1))) {
            return -1;
        }
        int runStart = pos;
        while (runStart > 0 && isAtomic(text.charAt(runStart - 1))) {
            runStart--;
        }
        return runStart;
    }

    /**
     * 推进起点回退：新窗口起点若严格落在原子串内部，退到串起点，让标识符完整归入本片。
     * 两道护栏：① 串起点必须仍在当前窗口起点之后——串从上一窗口就开始时回退会使 start
     * 停滞甚至倒退（死循环），此时保持原推进位，overlap 区内出现残影是接受的代价；
     * ② 位移超过 {@link #MAX_ATOMIC_SHIFT} 不迁移。选回退而非前推的理由见类注释。
     */
    private static int retreatStart(String text, int naiveStart, int currentStart) {
        int runStart = atomicRunStart(text, naiveStart);
        if (runStart > currentStart && naiveStart - runStart <= MAX_ATOMIC_SHIFT) {
            return runStart;
        }
        return naiveStart;
    }

    /**
     * 收口边界回退：窗口终点（句末断点或硬切）若严格落在原子串内部，退到串起点、
     * 整个串让给下一片；受同样的 {@link #MAX_ATOMIC_SHIFT} 上限约束。串起点还不得早于
     * 句末断点门槛（与 {@link #lastSentenceBreak} 同一比较口径 {@code (pos-start) > size*ratio}），
     * 避免为保标识符完整而产出碎片窗口——串起点太靠前时退无可退，保持原边界。
     */
    private static int retreatEnd(String text, int start, int end, int size, double ratio) {
        int runStart = atomicRunStart(text, end);
        if (runStart > start && (runStart - start) > size * ratio && end - runStart <= MAX_ATOMIC_SHIFT) {
            return runStart;
        }
        return end;
    }

    /** [from,to) 内去首尾空白；全空白返回 null。 */
    private static int[] trimBounds(String text, int from, int to) {
        int lead = from;
        while (lead < to && Character.isWhitespace(text.charAt(lead))) {
            lead++;
        }
        int trail = to - 1;
        while (trail >= lead && Character.isWhitespace(text.charAt(trail))) {
            trail--;
        }
        return trail < lead ? null : new int[] {lead, trail + 1};
    }

    /** 局部下标 → 清洗后全文偏移；落在段间分隔符上时钳到<b>下一段开头</b>
     * （分隔符自身无坐标，取最近的有内容边界）。 */
    private static int mapGlobal(List<int[]> ranges, int local) {
        for (int[] range : ranges) {
            if (local < range[0]) {
                return range[2];
            }
            if (local <= range[1]) {
                return range[2] + (local - range[0]);
            }
        }
        int[] last = ranges.get(ranges.size() - 1);
        return last[3];
    }
}
