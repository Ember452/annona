package io.annona.modules.voice.audio;

import java.time.Duration;
import java.time.Instant;

/**
 * 回声防护闸门（voice-adr：播放期半双工）：AI 音频播放期间丢弃麦克风上行，
 * 防止扬声器外放被自己的 ASR 抓回去形成自我循环（🅖 isAiSpeakingOrCooldown 同语义）。
 * 纯时钟判断、无 IO——可测性优先；浏览器 echoCancellation 是第一道，本闸门是第二道。
 *
 * <p>静音窗口 = 音频时长 × {@code SAFETY_FACTOR}（播完才解禁会掐掉用户抢答的第一个字，
 * 系数放大是安全侧）+ 冷却期。
 */
public final class EchoGate {

    /** 音频播放时长的放大系数：3G/弱网播放缓冲抖动比理想播放慢，按 1.5 倍估安全窗。 */
    static final double SAFETY_FACTOR = 1.5;
    /** 播完后仍静默的冷却期：扬声器残余混响 + ASR 缓冲里最后一帧的识别延迟。 */
    static final Duration COOLDOWN = Duration.ofMillis(300);

    private volatile Instant mutedUntil = Instant.EPOCH;

    /**
     * 宣告一段即将播放的音频（调用方在下发 audio_chunk 前调用）。
     *
     * @param audioBytes   16bit PCM 字节数
     * @param sampleRate   音频采样率（TTS 通道契约 24000）
     * @param now          当前时刻（调用方注入，可测）
     */
    public void markSpeaking(int audioBytes, int sampleRate, Instant now) {
        long millis = (long) (audioBytes / 2.0 / sampleRate * 1000 * SAFETY_FACTOR) + COOLDOWN.toMillis();
        Instant until = now.plusMillis(millis);
        if (until.isAfter(mutedUntil)) {
            mutedUntil = until;
        }
    }

    /** 该时刻是否应丢弃麦克风上行（连续多帧调用，now 由调用方注入）。 */
    public boolean isMuted(Instant now) {
        return now.isBefore(mutedUntil);
    }
}
