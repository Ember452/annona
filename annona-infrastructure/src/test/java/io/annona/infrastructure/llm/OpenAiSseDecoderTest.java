package io.annona.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OpenAiSseDecoder：SSE 帧解码")
class OpenAiSseDecoderTest {

    private List<String> decode(List<String> lines) {
        OpenAiSseDecoder decoder = new OpenAiSseDecoder();
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            out.addAll(decoder.feed(line));
        }
        String tail = decoder.flush();
        if (tail != null) {
            out.add(tail);
        }
        return out;
    }

    @Test
    @DisplayName("正常分帧：data 行 + 空行分发，[DONE] 原样透出")
    void normalFrames() {
        assertThat(decode(List.of(
            "data: {\"delta\":\"你\"}",
            "",
            "data: {\"delta\":\"好\"}",
            "",
            "data: [DONE]",
            ""))).containsExactly("{\"delta\":\"你\"}", "{\"delta\":\"好\"}", "[DONE]");
    }

    @Test
    @DisplayName("data: 后无空格的紧凑写法同样解析")
    void compactDataPrefix() {
        assertThat(decode(List.of("data:{\"a\":1}", ""))).containsExactly("{\"a\":1}");
    }

    @Test
    @DisplayName("跨 data 行的半个 JSON：多个 data 行按 SSE 规范以 \\n 拼接为一个载荷")
    void multiLineDataPayload() {
        assertThat(decode(List.of(
            "data: {\"text\":",
            "data: \"续\"}",
            ""))).containsExactly("{\"text\":\n\"续\"}");
    }

    @Test
    @DisplayName("注释行（keep-alive）与 event/id 等无关字段忽略，不打断 data 累积")
    void commentsAndOtherFieldsIgnored() {
        assertThat(decode(List.of(
            ": keep-alive",
            "event: token",
            "id: 1",
            "data: {\"x\":1}",
            ""))).containsExactly("{\"x\":1}");
    }

    @Test
    @DisplayName("上游省略末尾空行：flush 兜底补发最后一个载荷")
    void trailingPayloadWithoutBlankLine() {
        assertThat(decode(List.of("data: [DONE]"))).containsExactly("[DONE]");
    }

    @Test
    @DisplayName("空输入与纯注释：无载荷产出")
    void emptyInput() {
        assertThat(decode(List.of("", ": ping", ""))).isEmpty();
    }
}
