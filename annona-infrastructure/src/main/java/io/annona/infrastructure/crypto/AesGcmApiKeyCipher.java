package io.annona.infrastructure.crypto;

import io.annona.common.crypto.ApiKeyCipher;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256/GCM 实现（借 🅖 ApiKeyEncryptionService 的 nonce 结构与 SHA-256 密钥派生，
 * 两处必须改掉——已写进类注释防"顺手改回"）：
 * ① <b>无 DEV_FALLBACK_KEY 静默兜底</b>（上游常量会让忘配 KEK 悄悄加密成功，日后补配真
 *    KEK 时存量密文全体解不开，KEK ADR 否决表点名的数据事故）——secret 为空时加解密
 *    立即报 2903，宁拒不言；
 * ② 版本不符先于解密判定（轮换后旧行给出可行动文案，而不是含糊的 tag 校验失败）。
 *
 * <p>KEK 材料经 SHA-256 派生为 256-bit AES key：ANNONA_SECRET_KEY 是自由字符串
 * （openssl base64 只是推荐生成方式），派生让任意长度输入都能用，且不含长度校验分支。
 * 纯计算无外部调用，调用方按需包在 cpuExecutor 里（Service 层决定，见 llmprovider）。
 */
@Component
public class AesGcmApiKeyCipher implements ApiKeyCipher {

    private static final int NONCE_BYTES = 12;      // GCM 标准 96-bit
    private static final int TAG_BITS = 128;

    private final KekProperties properties;
    private final SecureRandom random = new SecureRandom();

    public AesGcmApiKeyCipher(KekProperties properties) {
        this.properties = properties;
    }

    @Override
    public EncryptedApiKey encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("plaintext 不得为空");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedApiKey(nonce, ciphertext, properties.getVersion(),
                ApiKeyCipher.mask(plaintext));
        } catch (BusinessException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new BusinessException(ErrorCode.PROVIDER_KEY_DECRYPT_FAILED,
                "密钥加密失败（服务端 KEK 状态异常）", e);
        }
    }

    @Override
    public String decrypt(EncryptedApiKey encrypted) {
        if (!properties.getVersion().equals(encrypted.kekVersion())) {
            throw new BusinessException(ErrorCode.PROVIDER_KEY_DECRYPT_FAILED,
                "密钥密文由 KEK " + encrypted.kekVersion() + " 加密，当前版本 "
                    + properties.getVersion() + "，请先执行轮换重加密");
        }
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, encrypted.nonce());
            return new String(cipher.doFinal(encrypted.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new BusinessException(ErrorCode.PROVIDER_KEY_DECRYPT_FAILED, null, e);
        }
    }

    private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            // 启动不拦（门控全关上下文必须能装配），使用期拒——与 StorageProperties 同纪律
            throw new BusinessException(ErrorCode.PROVIDER_KEY_DECRYPT_FAILED,
                "服务端未配置 KEK（ANNONA_SECRET_KEY），无法处理存储的 API Key");
        }
        byte[] derived = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            return cipher;
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }
}
