package io.annona.infrastructure.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TTS 通道配置（{@code annona.model.tts.*}，voice-adr §决策 2）。provider=none（默认）
 * 时不装配实现（语音降级为纯字幕文字面试）；dashscope = qwen-tts-realtime WebSocket
 * 直连；fake = 确定性音频。
 *
 * <p>默认值唯一出处是 {@code application.yaml} 的 {@code ${ENV:default}}，本类不留字段
 * 初值（PropertiesDefaultSourceTest 门禁）。错误后移到使用点，启动不 fail-fast。
 */
@ConfigurationProperties(prefix = "annona.model.tts")
public class TtsProperties {

    /** none = 关闭 TTS（降级纯字幕）；dashscope = qwen-tts-realtime WebSocket 直连；fake = 确定性音频。 */
    private String provider;

    /** Omni realtime 端点根（不带 model 参数，实现类按 url?model= 拼接）。 */
    private String url;

    /** API Key；可空——装配 dashscope 但 key 为空时在使用点报错误码。 */
    private String apiKey;

    /** 模型 id（如 qwen3-tts-flash-realtime）；同时是用量归属的 name() 口径。 */
    private String model;

    /** 默认音色（如 Cherry）；可被 TtsOptions.voice 覆盖。 */
    private String voice;

    /** 语言类型标识（DashScope language_type）。 */
    private String languageType;

    /** 语速（DashScope 0.5–2.0 区间标量）。 */
    private float speechRate;

    /** 音量（DashScope 0–100 区间标量）。 */
    private int volume;

    /** WebSocket 握手超时（秒）。 */
    private int connectTimeoutSeconds;

    /** 单次合成总超时（秒），超时放弃本次合成（降级字幕，不拖死会话）。 */
    private int synthesizeTimeoutSeconds;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getVoice() {
        return voice;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public String getLanguageType() {
        return languageType;
    }

    public void setLanguageType(String languageType) {
        this.languageType = languageType;
    }

    public float getSpeechRate() {
        return speechRate;
    }

    public void setSpeechRate(float speechRate) {
        this.speechRate = speechRate;
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getSynthesizeTimeoutSeconds() {
        return synthesizeTimeoutSeconds;
    }

    public void setSynthesizeTimeoutSeconds(int synthesizeTimeoutSeconds) {
        this.synthesizeTimeoutSeconds = synthesizeTimeoutSeconds;
    }
}
