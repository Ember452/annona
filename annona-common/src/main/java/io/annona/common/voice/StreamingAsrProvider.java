package io.annona.common.voice;

/**
 * 流式 ASR 端口：音频上行、转写下行，断句由<b>服务端 VAD</b> 完成（voice-adr §决策 1）。
 *
 * <p>契约（实现方必须遵守，消费方可以依赖）：
 * <ul>
 *   <li>{@link AsrConversation#sendAudio} 在通道就绪前调用实现可抛
 *       {@link IllegalStateException}；通道故障时<b>必须抛异常</b>而非吞掉——是否重启、
 *       何时告知用户由编排层决定（🅖 先例：sendAudio 抛出 → WS 层 restart）；</li>
 *   <li>{@link AsrConversation#close} 幂等；close 后回调不再触发；</li>
 *   <li>回调在实现方线程触发，消费方回调体必须轻（不得做阻塞 IO），重活自行转线程池
 *       （与 {@code ChatStreamListener} 同纪律）；</li>
 *   <li>{@code onPartial} 是<b>中间结果</b>（同句内会被后续 partial/final 取代），
 *       {@code onFinal} 是 VAD 断句定稿——字幕渲染消费 partial，落库/上下文只消费 final；</li>
 *   <li>每个回调保证至多触发一次 {@code onFinal} 后仍可能有新的 partial（新句开始）；
 *       会话生命周期内 onFinal 与句一一对应。</li>
 * </ul>
 *
 * <p>{@code name()} 是用量归属的模型口径（与 ModelProvider.name() 同语义）；
 * {@code channel()} 是供应通道标识（dashscope/fake），成本按通道聚合。
 */
public interface StreamingAsrProvider {

    /** 模型 id（用量归属口径）。 */
    String name();

    /** 供应通道标识（如 dashscope / fake）。 */
    String channel();

    /**
     * 开启一路 ASR 会话。同一时刻实现内部对同一路会话句柄的状态由实现保证；重复 start
     * 由实现决定语义（抛异常或替换），编排层不复用已 close 的句柄。
     *
     * @param options  会话级参数（语言、采样率）
     * @param listener 转写回调；实现必须保证 onReady 恰好一次，onError 后不再有转写回调
     * @return 会话句柄；连接是异步建立的，就绪以 {@code onReady} 为准
     */
    AsrConversation start(AsrOptions options, AsrListener listener);

    /** 一路已开始的 ASR 会话句柄。 */
    interface AsrConversation {

        /**
         * 推送一帧 PCM 音频（16kHz 16bit 单声道小端，建议 200ms/块）。
         *
         * @throws IllegalStateException 通道未就绪或已故障（编排层据此决定重启）
         */
        void sendAudio(byte[] pcm);

        /** 结束会话并释放上游连接；幂等。 */
        void close();
    }
}
