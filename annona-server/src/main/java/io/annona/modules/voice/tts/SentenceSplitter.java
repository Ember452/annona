package io.annona.modules.voice.tts;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 增量流的句级切分器（P3-03）：onDelta 逐段喂入，凑满完整句（以。！？!?…；;或换行
 * 结尾）即整句吐出；{@link #flush()} 收尾吐余量。纯有状态小类，非线程安全——
 * 只在单个流式回调线程上使用。
 *
 * <p>切分口径：标点跟随句子（保留在句尾），空句不产出；Markdown 列表符号等由
 * 提示词约束规避（voice 输出口径不含列表/代码块），此处不做清洗。
 */
public final class SentenceSplitter {

    /** 句终标点集合（中英文口语场景够用；引号内断句误差可接受）。 */
    private static final String SENTENCE_ENDS = "。！？!?…；;\n";

    private final StringBuilder buffer = new StringBuilder();

    /** 喂入一段增量，返回因此凑完整的句子（可能 0 句）。 */
    public List<String> feed(String delta) {
        List<String> sentences = new ArrayList<>();
        if (delta == null || delta.isEmpty()) {
            return sentences;
        }
        buffer.append(delta);
        int start = 0;
        for (int i = 0; i < buffer.length(); i++) {
            if (SENTENCE_ENDS.indexOf(buffer.charAt(i)) >= 0) {
                String sentence = buffer.substring(start, i + 1);
                if (!sentence.isBlank()) {
                    sentences.add(sentence.trim());
                }
                start = i + 1;
            }
        }
        buffer.delete(0, start);
        return sentences;
    }

    /** 流终态调用：吐出未以标点结尾的余量（可能为空）。 */
    public List<String> flush() {
        List<String> rest = new ArrayList<>();
        if (buffer.length() > 0 && !buffer.toString().isBlank()) {
            rest.add(buffer.toString().trim());
        }
        buffer.setLength(0);
        return rest;
    }
}
