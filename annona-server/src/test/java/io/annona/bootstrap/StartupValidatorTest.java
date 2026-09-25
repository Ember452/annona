package io.annona.bootstrap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 纯逻辑单测（P0-08）：{@link StartupValidator#validate(org.springframework.core.env.Environment)}
 * 在四种 profile/KEK 组合下的行为。构造 Spring 事件成本高，validate 是 package-private static
 * 让本测试直接调；onApplicationEvent 只是一行 delegate，不单测。
 */
@DisplayName("StartupValidator KEK 校验（P0-08）")
class StartupValidatorTest {

    @Nested
    @DisplayName("prod profile 场景")
    class ProdProfile {

        @Test
        @DisplayName("缺 KEK → 抛错，消息含可执行动作 openssl rand")
        void prodWithoutKekThrows() {
            MockEnvironment env = new MockEnvironment()
                .withProperty("annona.startup.require-kek", "true");
            // 不设 annona.kek.secret
            assertThatThrownBy(() -> StartupValidator.validate(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANNONA_SECRET_KEY")
                .hasMessageContaining("openssl rand -base64 32")
                .hasMessageContaining("model-api-key-adr");
        }

        @Test
        @DisplayName("KEK 是 dev fallback 占位常量 → 也拒绝（防误配 prod profile 用了 dev 默认）")
        void prodWithDevFallbackThrows() {
            MockEnvironment env = new MockEnvironment()
                .withProperty("annona.startup.require-kek", "true")
                .withProperty("annona.kek.secret", StartupValidator.DEV_FALLBACK_PLACEHOLDER);
            assertThatThrownBy(() -> StartupValidator.validate(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev-only fallback");
        }

        @Test
        @DisplayName("KEK 是真实值 → 通过")
        void prodWithRealKekPasses() {
            MockEnvironment env = new MockEnvironment()
                .withProperty("annona.startup.require-kek", "true")
                .withProperty("annona.kek.secret", "a-real-kek-from-vault-or-env-32bytes");
            assertThatCode(() -> StartupValidator.validate(env)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("dev / test profile 场景")
    class DevProfile {

        @Test
        @DisplayName("require-kek=false + 缺 KEK → 不抛（本机开发默认能启动）")
        void devWithoutKekPasses() {
            MockEnvironment env = new MockEnvironment()
                .withProperty("annona.startup.require-kek", "false");
            assertThatCode(() -> StartupValidator.validate(env)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("未显式设 require-kek 时默认 true（安全侧默认），空 KEK 抛错")
        void defaultIsRequireKekTrue() {
            MockEnvironment env = new MockEnvironment();
            // 什么都不设，走 StartupValidator 里的 Boolean.TRUE 默认
            assertThatThrownBy(() -> StartupValidator.validate(env))
                .isInstanceOf(IllegalStateException.class);
        }
    }
}
