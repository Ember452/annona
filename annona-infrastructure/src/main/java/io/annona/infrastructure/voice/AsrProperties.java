package io.annona.infrastructure.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ASR 通道配置（{@code annona.model.asr.*}，voice-adr §决策 2）。provider=none（默认）
 * 时不装配实现，语音使用点报语音段错误码；dashscope = Omni realtime WebSocket 直连；
 * fake = 确定性转写（无 Key 环境跑通链路）。
 *
 * <p>默认值唯一出处是 {@code application.yaml} 的 {@code ${ENV:default}}，本类不留字段
 * 初值（PropertiesDefaultSourceTest 门禁）。错误后移到使用点，启动不 fail-fast
 * （与 chat/embedding 同口径，knowledge-ingestion-adr §决策 9）。
 */
@ConfigurationProperties(prefix = "annona.model.asr")
public class AsrProperties {

    /** none = 关闭 ASR；dashscope = Omni realtime WebSocket 直连；fake = 确定性转写。 */
    private String provider;

    /** Omni realtime 端点根（不带 model 参数，实现类按 url?model= 拼接）。 */
    private String url;

    /** API Key；可空——装配 dashscope 但 key 为空时在使用点报错误码。 */
    private String apiKey;

    /** 模型 id（如 qwen3-asr-flash-realtime）；同时是用量归属的 name() 口径。 */
    private String model;

    /** 识别语言（BCP-47 风格，默认 zh 走 yaml）。 */
    private String language;

    /** 上行音频格式串（随 session.update 下发；协议校准点，改 yaml 不重编译）。 */
    private String inputAudioFormat;

    /** 断句模式：server_vad（默认）= 服务端 VAD 自动断句；manual = 手动 commit。 */
    private String vadType;

    /** VAD 灵敏度阈值（0–1，越大越保守）。 */
    private float vadThreshold;

    /** 静音判定时长（毫秒），达到即断句。 */
    private int vadSilenceMs;

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

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getInputAudioFormat() {
        return inputAudioFormat;
    }

    public void setInputAudioFormat(String inputAudioFormat) {
        this.inputAudioFormat = inputAudioFormat;
    }

    public String getVadType() {
        return vadType;
    }

    public void setVadType(String vadType) {
        this.vadType = vadType;
    }

    public float getVadThreshold() {
        return vadThreshold;
    }

    public void setVadThreshold(float vadThreshold) {
        this.vadThreshold = vadThreshold;
    }

    public int getVadSilenceMs() {
        return vadSilenceMs;
    }

    public void setVadSilenceMs(int vadSilenceMs) {
        this.vadSilenceMs = vadSilenceMs;
    }
}
