/**
 * 语音通道端口（voice-adr §决策 1）：流式 ASR 与一次性 TTS。与 chat 的
 * {@code StreamingChatProvider} 同先例——无第三方实现方需求，属内部解耦端口，
 * 不进对外发布的 spi；实现（fake / dashscope）都落在 annona-infrastructure。
 *
 * <p>音频契约：上行 PCM 16kHz 16bit 单声道小端；TTS 返回 PCM 24kHz 16bit 单声道
 * （DashScope 实现的输出格式，前端播放负责封装 WAV 头）。音频链路的并发调度
 * （句子级并发 TTS、按序推送）是 voice 编排层职责，不属于端口契约。
 */
package io.annona.common.voice;
