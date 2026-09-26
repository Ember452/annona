package io.annona.shared.direction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("direction.key 生成与校验（纯逻辑）")
class DirectionKeysTest {

    @Test
    @DisplayName("纯 ASCII 名称按 slug 推导：小写、非法字符转单连字符、去首尾连字符")
    void slugifiesAsciiName() {
        assertThat(DirectionKeys.generate("Java Concurrency", null)).isEqualTo("java-concurrency");
        assertThat(DirectionKeys.generate("  C++ / STL  ", null)).isEqualTo("c-stl");
    }

    @Test
    @DisplayName("含非 ASCII 的名称不强行转写，落 custom-<8位hex>")
    void customPrefixForNonAsciiName() {
        String key = DirectionKeys.generate("刑法学", null);

        assertThat(key).startsWith("custom-").hasSize("custom-".length() + 8);
    }

    @Test
    @DisplayName("混合 ASCII 与中文的名称同样走 custom-，避免逐词截断丢语义")
    void mixedNameAlsoFallsBackToCustom() {
        assertThat(DirectionKeys.generate("Java 并发", null)).startsWith("custom-");
    }

    @Test
    @DisplayName("超长 ASCII 名称截断到 64 字符且不带悬挂连字符")
    void truncatesLongSlug() {
        String key = DirectionKeys.generate("a".repeat(80) + " b", null);

        assertThat(key).hasSizeLessThanOrEqualTo(DirectionKeys.MAX_LENGTH)
            .doesNotEndWith("-");
    }

    @Test
    @DisplayName("显式提供合法 key 时原样采用（去首尾空白），忽略名称推导")
    void usesProvidedKey() {
        assertThat(DirectionKeys.generate("随便什么名", " my-key ")).isEqualTo("my-key");
    }

    @Test
    @DisplayName("显式 key 非法（大写/空段/超长）抛 IllegalArgumentException")
    void rejectsInvalidProvidedKey() {
        assertThatThrownBy(() -> DirectionKeys.generate("x", "My-Key"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DirectionKeys.generate("x", "-bad-"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DirectionKeys.generate("x", "k".repeat(65)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("同一批中文命名的生成结果互不冲突（随机段唯一性）")
    void randomSegmentStaysUnique() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            keys.add(DirectionKeys.generate("数据结构", null));
        }
        assertThat(keys).hasSize(100);
    }
}
