package io.annona.modules.identity.service;

import org.springframework.security.crypto.scrypt.SCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * scrypt 口令哈希与透明重哈希判定（identity-credential-storage-adr §决策 §2）。
 *
 * <p>参数取 ADR 的 N=2^15(32768)/r=8/p=1、keyLength=64、salt=16（纯 Java，无 native，
 * 区别于上游 summer 的 2^14/16——以本项目 ADR 为准）。当前参数写进
 * {@code app_user.password_algo}，登录成功时若 algo 不匹配即透明重哈希。
 */
@Component
public class PasswordHasher {

    /** 当前算法+参数标识，落入 {@code password_algo}（VARCHAR(32) 内）。 */
    public static final String ALGO_TAG = "scrypt:32768,8,1";

    private final SCryptPasswordEncoder encoder =
        new SCryptPasswordEncoder(32768, 8, 1, 64, 16);

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodedHash) {
        return encoder.matches(rawPassword, encodedHash);
    }

    public String currentAlgo() {
        return ALGO_TAG;
    }

    /** 存储时的 algo 与当前参数不一致 → 需要重哈希（算法升级/参数变大时不需用户改密码）。 */
    public boolean needsRehash(String storedAlgo) {
        return !ALGO_TAG.equals(storedAlgo);
    }
}
