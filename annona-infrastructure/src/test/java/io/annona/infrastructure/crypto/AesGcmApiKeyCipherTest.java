package io.annona.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.common.crypto.ApiKeyCipher;
import io.annona.common.crypto.ApiKeyCipher.EncryptedApiKey;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AES/GCM 往返、nonce 独立性与失败语义（纯计算单测，无 Spring 上下文）。 */
class AesGcmApiKeyCipherTest {

    private static KekProperties kek(String secret, String version) {
        KekProperties properties = new KekProperties();
        properties.setSecret(secret);
        properties.setVersion(version);
        return properties;
    }

    private final AesGcmApiKeyCipher cipher = new AesGcmApiKeyCipher(kek("test-kek-material", "v1"));

    @Test
    @DisplayName("往返：明文 → 密文 → 明文相等，掩码随行产出")
    void roundTrip() {
        EncryptedApiKey encrypted = cipher.encrypt("sk-abcdef123456xyz");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("sk-abcdef123456xyz");
        assertThat(encrypted.masked()).isEqualTo("sk-a…yz");
        assertThat(encrypted.kekVersion()).isEqualTo("v1");
    }

    @Test
    @DisplayName("同明文两次加密：nonce 与密文都不同，且各自可解")
    void nonceIsPerRecord() {
        var a = cipher.encrypt("sk-same-key-value");
        var b = cipher.encrypt("sk-same-key-value");
        assertThat(Arrays.equals(a.nonce(), b.nonce())).isFalse();
        assertThat(Arrays.equals(a.ciphertext(), b.ciphertext())).isFalse();
        assertThat(cipher.decrypt(a)).isEqualTo(cipher.decrypt(b));
    }

    @Test
    @DisplayName("GCM 形状：nonce 96-bit，密文含 128-bit 认证 tag")
    void gcmShapes() {
        var encrypted = cipher.encrypt("12345678");   // 8 字节明文
        assertThat(encrypted.nonce()).hasSize(12);
        assertThat(encrypted.ciphertext()).hasSize(8 + 16);
    }

    @Test
    @DisplayName("篡改密文 → 2903（tag 校验失败不静默返回垃圾）")
    void tamperedCiphertextFails() {
        var encrypted = cipher.encrypt("sk-integrity-test");
        var tampered = encrypted.ciphertext().clone();
        tampered[0] ^= 0x01;
        assertThatThrownBy(() -> cipher.decrypt(
            new EncryptedApiKey(encrypted.nonce(), tampered, "v1", encrypted.masked())))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode())
                    .isEqualTo(ErrorCode.PROVIDER_KEY_DECRYPT_FAILED.getCode()));
    }

    @Test
    @DisplayName("KEK 版本不符 → 2903 且文案指向轮换（先于解密判定）")
    void kekVersionMismatch() {
        var encrypted = cipher.encrypt("sk-rotation-case");
        var other = new AesGcmApiKeyCipher(kek("test-kek-material", "v2"));
        assertThatThrownBy(() -> other.decrypt(
            new EncryptedApiKey(encrypted.nonce(), encrypted.ciphertext(),
                encrypted.kekVersion(), encrypted.masked())))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessage()).contains("v1").contains("轮换"));
    }

    @Test
    @DisplayName("KEK 未配置：encrypt/decrypt 都拒绝，绝不静默兜底（否决上游 DEV_FALLBACK_KEY）")
    void missingKekRejects() {
        var unconfigured = new AesGcmApiKeyCipher(kek("", "v1"));
        assertThatThrownBy(() -> unconfigured.encrypt("sk-x")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("掩码口径：<8 位全星；空白 → ***")
    void maskRules() {
        assertThat(ApiKeyCipher.mask("short")).isEqualTo("*****");
        assertThat(ApiKeyCipher.mask("abcdefghijklmnop"))
            .isEqualTo(ApiKeyCipher.mask("abcdefghijklmnop"));
        assertThat(ApiKeyCipher.mask(null)).isEqualTo("***");
        assertThat(ApiKeyCipher.mask(" ")).isEqualTo("***");
    }
}
