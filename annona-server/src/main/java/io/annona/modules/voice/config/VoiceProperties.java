package io.annona.modules.voice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语音面试对话轮配置（{@code annona.voice.*}，voice-adr 修订 1）。
 *
 * <p>默认值唯一出处是 {@code application.yaml} 的 {@code ${ENV:default}}，本类不留字段
 * 初值（PropertiesDefaultSourceTest 门禁）。
 */
@ConfigurationProperties(prefix = "annona.voice")
public class VoiceProperties {

    /** 开场白文本（会话创建时落快照，之后改配置不影响已开会话）。 */
    private String opening;

    /** 一次语音面试的题库题目数上限（activePool 截取）。 */
    private int questionCount;

    /** 句级并发 TTS 的每会话并发上限（信号量，🅖 同款 3）。 */
    private int ttsMaxConcurrent;

    /** 单句 TTS 合成超时（秒）；超时句跳过，整段按句数估总预算。 */
    private int ttsChunkTimeoutSeconds;

    /** LLM 历史装配的最大轮数（超出截断最旧的；完整压缩器批 3）。 */
    private int historyMaxTurns;

    public String getOpening() {
        return opening;
    }

    public void setOpening(String opening) {
        this.opening = opening;
    }

    public int getQuestionCount() {
        return questionCount;
    }

    public void setQuestionCount(int questionCount) {
        this.questionCount = questionCount;
    }

    public int getTtsMaxConcurrent() {
        return ttsMaxConcurrent;
    }

    public void setTtsMaxConcurrent(int ttsMaxConcurrent) {
        this.ttsMaxConcurrent = ttsMaxConcurrent;
    }

    public int getTtsChunkTimeoutSeconds() {
        return ttsChunkTimeoutSeconds;
    }

    public void setTtsChunkTimeoutSeconds(int ttsChunkTimeoutSeconds) {
        this.ttsChunkTimeoutSeconds = ttsChunkTimeoutSeconds;
    }

    public int getHistoryMaxTurns() {
        return historyMaxTurns;
    }

    public void setHistoryMaxTurns(int historyMaxTurns) {
        this.historyMaxTurns = historyMaxTurns;
    }
}
