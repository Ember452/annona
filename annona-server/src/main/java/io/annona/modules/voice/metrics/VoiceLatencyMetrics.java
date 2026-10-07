package io.annona.modules.voice.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 语音链路分段延迟埋点（P3-01 起，P3-06 出预算表）。口径红线（设计文档 §10）：
 * <b>端到端（停止说话→首包音频）是唯一对外验收数字</b>；本类现在只挂两个分段计时器，
 * 端到端 timer 与预算表在批 2（LLM 进环后才有完整链路）落地——分段数字只用于链路归因，
 * 不许当对外指标引用（P3-06 验收原话：不许靠 TTS 首包数字充数）。
 *
 * <p>Timer 天然产出 P50/P95/Max 分位数（Micrometer percentile histograms 按部署侧开启）。
 */
@Component
public class VoiceLatencyMetrics {

    private final Timer asrFirstPartial;
    private final Timer ttsSynthesize;

    public VoiceLatencyMetrics(MeterRegistry registry) {
        this.asrFirstPartial = Timer.builder("voice.asr.first-partial")
            .description("第一帧上行音频到首个 partial 转写的时间（ASR 通道感知延迟）")
            .register(registry);
        this.ttsSynthesize = Timer.builder("voice.tts.synthesize")
            .description("单句 TTS 合成端到端耗时（一次性端口语义，含建连）")
            .register(registry);
    }

    /** 首帧上行音频 → 首个 partial 的耗时；每路 ASR 会话只记一次。 */
    public void recordAsrFirstPartial(Duration elapsed) {
        asrFirstPartial.record(elapsed);
    }

    /** 单句 TTS 合成耗时。 */
    public void recordTtsSynthesize(Duration elapsed) {
        ttsSynthesize.record(elapsed);
    }
}
