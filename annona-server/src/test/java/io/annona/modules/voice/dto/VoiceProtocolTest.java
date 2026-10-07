package io.annona.modules.voice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * /ws/voice 协议编解码规格（批 1）。与前端 voiceProtocol.test.ts 成对——改协议两端同批改。
 */
@Tag("slice")
@DisplayName("VoiceProtocol：帧编解码")
class VoiceProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("parse：五类上行帧字段各取所需")
    void parsesClientFrames() throws IOException {
        var audio = VoiceProtocol.parse("{\"type\":\"audio\",\"data\":\"QUJD\"}");
        assertThat(audio.type()).isEqualTo("audio");
        assertThat(audio.data()).isEqualTo("QUJD");

        var control = VoiceProtocol.parse("{\"type\":\"control\",\"action\":\"pause\"}");
        assertThat(control.action()).isEqualTo("pause");

        var submit = VoiceProtocol.parse("{\"type\":\"submit\",\"text\":\"手动作答\"}");
        assertThat(submit.text()).isEqualTo("手动作答");

        var start = VoiceProtocol.parse("{\"type\":\"start\",\"directionId\":\"" + UUID_SHAPE + "\"}");
        assertThat(start.directionId()).isEqualTo(UUID_SHAPE);
    }

    private static final String UUID_SHAPE = "11111111-1111-1111-1111-111111111111";

    @Test
    @DisplayName("parse：缺 type 抛 IOException（调用方按 2603 处理）")
    void rejectsMissingType() {
        assertThatThrownBy(() -> VoiceProtocol.parse("{\"data\":\"x\"}")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> VoiceProtocol.parse("not-json")).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("subtitle 帧带 isFinal；audio_chunk 带 seq/isLast；error 带 recoverable")
    void buildsServerFrames() throws IOException {
        var subtitle = mapper.readTree(VoiceProtocol.subtitle("你好", true));
        assertThat(subtitle.path("type").asText()).isEqualTo("subtitle");
        assertThat(subtitle.path("text").asText()).isEqualTo("你好");
        assertThat(subtitle.path("isFinal").asBoolean()).isTrue();

        var chunk = mapper.readTree(VoiceProtocol.audioChunk("QUJD", 3, true));
        assertThat(chunk.path("seq").asInt()).isEqualTo(3);
        assertThat(chunk.path("isLast").asBoolean()).isTrue();

        var error = mapper.readTree(VoiceProtocol.error(2602, "中断", true));
        assertThat(error.path("code").asInt()).isEqualTo(2602);
        assertThat(error.path("recoverable").asBoolean()).isTrue();
    }
}
