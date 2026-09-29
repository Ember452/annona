package io.annona.common.crypto;

/**
 * API Key 加解密端口（2026-09-25-model-api-key-adr §决策 2 的三列结构在此落形）。
 * 端口在 common、AES/GCM 实现在 infrastructure.crypto（SessionStore 同型依赖倒置；
 * javax.crypto 属 JDK，但密钥材料是部署配置，实现归 infra 的厂商/配置边界）。
 *
 * <p>契约：{@code encrypt} 每次产出独立随机 nonce（同明文两次密文必不同）；
 * {@code decrypt} 对篡改密文或 KEK 不匹配抛
 * {@code io.annona.common.exception.BusinessException}（PROVIDER_KEY_DECRYPT_FAILED），
 * 禁止裸抛；解密所得明文的生命周期止于调用方栈内——不进缓存、不进日志（ADR §决策 5）。
 */
public interface ApiKeyCipher {

    /**
     * 加密结果（= llm_provider_config 的四列落库形状）。
     *
     * @param nonce       96-bit GCM IV
     * @param ciphertext  密文（含 128-bit 认证 tag）
     * @param kekVersion  加密时的 KEK 版本（轮换支点）
     * @param masked      入库时同步生成的掩码（对外只回这一列）
     */
    record EncryptedApiKey(byte[] nonce, byte[] ciphertext, String kekVersion, String masked) {
    }

    /** 加密明文 Key；{@code plaintext} 为 null/空白时抛 IllegalArgumentException。 */
    EncryptedApiKey encrypt(String plaintext);

    /** 解密回明文（仅服务端消费链使用，永不进响应体）。 */
    String decrypt(EncryptedApiKey encrypted);

    /**
     * 掩码：长度 ≥8 → 首4 + "…"+ 尾2；更短全星。
     * 单独成方法而非藏在 encrypt 里：列表接口要对历史行重算掩码时不必解密。
     */
    static String mask(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return "***";
        }
        if (plaintext.length() < 8) {
            return "*".repeat(plaintext.length());
        }
        return plaintext.substring(0, 4) + "…" + plaintext.substring(plaintext.length() - 2);
    }
}
