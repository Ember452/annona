package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.AnnonaApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 端到端 fail-fast 断言（P0-08 场景 3）：连一个<b>没有 pgvector 的 vanilla {@code postgres:16}</b>
 * 时应用应拒绝启动并给出可执行提示。
 *
 * <p>本机不跑（无 Docker）；CI workflow 需为此用例额外起一个 vanilla {@code postgres:16} service
 * （与 {@link FlywayBaselineIT} 用的 {@code pgvector/pgvector:pg16} 分开），并把
 * {@code BAD_DB_DATASOURCE_URL} 环境变量指过去。P0-12（B5）落 workflow 时补上。
 *
 * <p>本用例走完整 {@code SpringApplication.run()} 生命周期，因此同时验证了两件事：
 * <ul>
 *   <li>{@code @ConditionalOnBean(DataSource.class)} 让 {@code FlywayExtensionGuard}
 *       在 DataSource 真的可用时才装配；</li>
 *   <li>{@code FlywayMigrationStrategy} 拦截 {@code flyway.migrate()}，在扩展缺失时
 *       抛带可执行动作的 {@link IllegalStateException}。</li>
 * </ul>
 */
@Tag("docker")
@DisplayName("PG 无 pgvector 扩展应拒绝启动（P0-08 场景 3，CI 才跑）")
class ExtensionMissingIT {

    @Test
    @DisplayName("连到 vanilla postgres:16 时启动抛错，消息含 pgvector/pgvector:pg16")
    void vanillaPostgresWithoutPgvectorFailsBoot() {
        String badUrl = System.getenv("BAD_DB_DATASOURCE_URL");
        assertThatThrownBy(() -> new SpringApplicationBuilder(AnnonaApplication.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.profiles.active=prod",
                "annona.kek.secret=dummy-kek-for-test-only-not-empty",
                "spring.datasource.url=" + badUrl,
                "spring.datasource.username=" + System.getenv().getOrDefault("BAD_DB_USER", "postgres"),
                "spring.datasource.password=" + System.getenv().getOrDefault("BAD_DB_PASSWORD", "postgres"),
                "spring.main.banner-mode=off",
                "logging.level.root=OFF")
            .run())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Missing required PostgreSQL extensions")
            .hasMessageContaining("pgvector/pgvector:pg16");
    }
}
