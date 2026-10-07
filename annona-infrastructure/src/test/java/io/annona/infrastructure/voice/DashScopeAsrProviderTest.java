package io.annona.infrastructure.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * DashScope ASR partial 文本提取规格（🅖 QwenAsrServiceTest 的
 * extractTranscriptPayload_textAndStash / mixedPrefixSuffix 行为规格移植）。
 * qwen-asr-realtime 的 partial 事件是 text（确认前缀）+ stash（草稿后缀），
 * 多形状兼容是服务端事件的既知现实（voice-adr §决策 2）。
 */
@Tag("slice")
@DisplayName("DashScopeAsrProvider.extractTranscriptPayload：partial 多形状提取")
class DashScopeAsrProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    @DisplayName("completed 事件：transcript 直取")
    void transcriptDirect() throws Exception {
        var node = json("{\"type\":\"conversation.item.input_audio_transcription.completed\","
            + "\"transcript\":\"你好世界\"}");
        assertThat(DashScopeAsrProvider.extractTranscriptPayload(node)).isEqualTo("你好世界");
    }

    @Test
    @DisplayName("text+stash 拼接（确认前缀 + 草稿后缀）")
    void textAndStash() throws Exception {
        var node = json("{\"text\":\"你好\",\"stash\":\"世\"}");
        assertThat(DashScopeAsrProvider.extractTranscriptPayload(node)).isEqualTo("你好世");
    }

    @Test
    @DisplayName("text+stash 混合形态：前缀或后缀任一缺失也能拼")
    void mixedPrefixSuffix() throws Exception {
        assertThat(DashScopeAsrProvider.extractTranscriptPayload(json("{\"stash\":\"只有草稿\"}")))
            .isEqualTo("只有草稿");
        assertThat(DashScopeAsrProvider.extractTranscriptPayload(json("{\"text\":\"只有前缀\"}")))
            .isEqualTo("只有前缀");
    }

    @Test
    @DisplayName("delta 为字符串或对象（内含 text/transcript）都兼容")
    void deltaShapes() throws Exception {
        assertThat(DashScopeAsrProvider.extractTranscriptPayload(json("{\"delta\":\"增量\"}")))
            .isEqualTo("增量");
        assertThat(DashScopeAsrProvider.extractTranscriptPayload(json("{\"delta\":{\"text\":\"对象增量\"}}")))
            .isEqualTo("对象增量");
        assertThat(DashScopeAsrProvider.extractTranscriptPayload(json("{\"delta\":{\"transcript\":\"t\"}}")))
            .isEqualTo("t");
    }

    @Test
    @DisplayName("item.transcript 兜底；无文本返回 null")
    void itemFallbackAndNull() throws Exception {
        assertThat(DashScopeAsrProvider.extractTranscriptPayload(
            json("{\"item\":{\"transcript\":\"条目文本\"}}"))).isEqualTo("条目文本");
        assertThat(DashScopeAsrProvider.extractTranscriptPayload(json("{\"type\":\"irrelevant\"}")))
            .isNull();
    }
}
