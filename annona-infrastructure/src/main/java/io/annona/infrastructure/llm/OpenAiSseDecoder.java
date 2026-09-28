package io.annona.infrastructure.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容流的 SSE 帧解码器（表驱动单测直接喂行，qa-streaming-adr §决策 3）。
 * 只关心 data 行：多个 data 行按 SSE 规范以 {@code \n} 拼接成完整载荷、空行触发分发、
 * {@code :} 开头的注释/keep-alive 忽略；{@code event:/id:/retry:} 等本协议不用的字段忽略。
 * 载荷原样返回（含 {@code [DONE]} 哨兵），JSON 解析归 Provider——测试可以喂任意载荷。
 */
final class OpenAiSseDecoder {

    private final StringBuilder pending = new StringBuilder();

    /** @return 该行触发的完整载荷（0 个或 1 个）。 */
    List<String> feed(String line) {
        if (line == null || line.isEmpty()) {
            return flushAsList();
        }
        if (line.startsWith(":")) {
            return List.of();
        }
        if (line.startsWith("data:")) {
            String value = line.substring(5);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (pending.length() > 0) {
                pending.append('\n');
            }
            pending.append(value);
        }
        return List.of();
    }

    /** 流结束时补发未分发的载荷（上游省略末尾空行的情况）；无则返回 {@code null}。 */
    String flush() {
        if (pending.length() == 0) {
            return null;
        }
        String out = pending.toString();
        pending.setLength(0);
        return out;
    }

    private List<String> flushAsList() {
        String out = flush();
        if (out == null) {
            return List.of();
        }
        List<String> outList = new ArrayList<>(1);
        outList.add(out);
        return outList;
    }
}
