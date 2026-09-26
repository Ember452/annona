package io.annona.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PasswordHasher：scrypt 哈希与透明重哈希判定")
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    @DisplayName("哈希可校验，且不等于明文、两次哈希不同（随机 salt）")
    void hashMatchesAndIsSalted() {
        String hash = hasher.hash("Passw0rd!");
        assertThat(hash).isNotEqualTo("Passw0rd!");
        assertThat(hasher.matches("Passw0rd!", hash)).isTrue();
        assertThat(hasher.matches("wrong", hash)).isFalse();
        assertThat(hasher.hash("Passw0rd!")).isNotEqualTo(hash);
    }

    @Test
    @DisplayName("algo 匹配当前参数不重哈希；旧参数触发重哈希")
    void needsRehashByAlgoTag() {
        assertThat(hasher.needsRehash(hasher.currentAlgo())).isFalse();
        assertThat(hasher.needsRehash("scrypt:16384,16,1")).isTrue();
        assertThat(hasher.needsRehash(null)).isTrue();
    }
}
