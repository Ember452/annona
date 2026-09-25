package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.AnnonaApplication;
import io.annona.bootstrap.StartupValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 端到端 fail-fast 断言（P0-08）：以 prod profile 无 {@code ANNONA_SECRET_KEY} 启动
 * {@link AnnonaApplication}，Spring Boot 必须在任何 Bean 装配之前抛出 {@link IllegalStateException}。
 *
 * <p>这个测试是"KEK 检查真的在应用启动路径上生效"的最强证据：它绕开单元测试直接调
 * {@link io.annona.bootstrap.StartupValidator#validate(org.springframework.core.env.Environment)}
 * 的语法，走完整的 {@code SpringApplication.run()} 生命周期，验证
 * {@code META-INF/spring.factories} 里的 listener 注册也确实被 Boot 加载。
 *
 * <p>{@code spring.profiles.active=prod} 但 {@code annona.startup.require-kek=true} 且 KEK
 * 属性未注入（{@code application-prod.yaml} 里 KEK 只从 {@code ${ANNONA_SECRET_KEY:}} 读，
 * CI 也不设这个 env）→ StartupValidator 应该抛。DataSource / Flyway 的 autoconfig 都还
 * 没轮到初始化，所以本测试不需要真 PG。
 */
@DisplayName("prod profile 缺 KEK 应拒绝启动（P0-08 场景 1）")
class ProdProfileWithoutKekIT {

    @Test
    @DisplayName("SpringApplicationBuilder.run() 抛 IllegalStateException，消息含 ANNONA_SECRET_KEY")
    void bootWithProdProfileAndNoKekFailsFast() {
        // 命令行参数优先级最高（高于 defaultProperties 与 profile yaml），直接
        // 把 active profile 强制为 prod（不是追加）。application-prod.yaml
        // 里 require-kek=true 与 kek.secret=${ANNONA_SECRET_KEY:} 同时生效；
        // StartupValidator 在 ApplicationEnvironmentPreparedEvent 上抛，早于 DataSource。
        assertThatThrownBy(() -> new SpringApplicationBuilder(AnnonaApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.profiles.active=prod",
                "--spring.main.banner-mode=off",
                "--logging.level.root=OFF"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ANNONA_SECRET_KEY")
            .hasMessageContaining("openssl rand -base64 32");
    }
}
