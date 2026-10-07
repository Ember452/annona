package io.annona.modules.voice.audio;

import java.io.ByteArrayOutputStream;

/**
 * PCM → WAV（RIFF）封装（🅖 VoiceInterviewWebSocketHandler.convertPcmToWav 同款实现）：
 * 给裸 PCM 加 44 字节标准头，浏览器 {@code <audio>} / Blob 可直接播放。
 * 纯函数、无状态；调用方负责给出与数据一致的真实采样率。
 */
public final class PcmWav {

    private PcmWav() {
    }

    /**
     * @param pcm        16bit 单声道小端 PCM 裸数据
     * @param sampleRate 采样率（Hz）；TTS 通道契约是 24000
     */
    public static byte[] wrap(byte[] pcm, int sampleRate) {
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataLen = pcm.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLen);
        out.writeBytes(bytes("RIFF"));
        writeInt(out, 36 + dataLen);
        out.writeBytes(bytes("WAVE"));
        out.writeBytes(bytes("fmt "));
        writeInt(out, 16);
        writeShort(out, (short) 1);
        writeShort(out, (short) channels);
        writeInt(out, sampleRate);
        writeInt(out, byteRate);
        writeShort(out, (short) blockAlign);
        writeShort(out, (short) bitsPerSample);
        out.writeBytes(bytes("data"));
        writeInt(out, dataLen);
        out.writeBytes(pcm);
        return out.toByteArray();
    }

    private static byte[] bytes(String ascii) {
        return ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream out, short value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }
}
