package io.annona.modules.voice.tts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 句级切分规格（P3-03）：标点跟随句尾、余量 flush、空段不产出。
 */
@Tag("slice")
@DisplayName("SentenceSplitter：LLM 增量切句")
class SentenceSplitterTest {

    @Test
    @DisplayName("中英文标点都断句，标点保留在句尾")
    void splitsOnPunctuation() {
        var splitter = new SentenceSplitter();
        assertThat(splitter.feed("你好。请回答！")).isEqualTo(List.of("你好。", "请回答！"));
        assertThat(splitter.feed("Really? Yes;")).isEqualTo(List.of("Really?", "Yes;"));
    }

    @Test
    @DisplayName("增量跨句：标点落在后一段也能在边界切出")
    void splitsAcrossDeltas() {
        var splitter = new SentenceSplitter();
        assertThat(splitter.feed("第一句的前半")).isEmpty();
        assertThat(splitter.feed("。第二句。")).isEqualTo(List.of("第一句的前半。", "第二句。"));
    }

    @Test
    @DisplayName("换行断句；flush 吐出未以标点结尾的余量；空余量不产出")
    void newlineAndFlush() {
        var splitter = new SentenceSplitter();
        assertThat(splitter.feed("第一行\n第二行没有标点")).isEqualTo(List.of("第一行"));
        assertThat(splitter.flush()).isEqualTo(List.of("第二行没有标点"));
        assertThat(new SentenceSplitter().feed("")).isEmpty();
        assertThat(new SentenceSplitter().flush()).isEmpty();
    }
}
