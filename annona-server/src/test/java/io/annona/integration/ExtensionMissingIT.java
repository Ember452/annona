package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.AnnonaApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
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
        // 故意不用 assumeTrue 跳过：缺少环境就是接线错误，必须响而不是静默过关。
        // 该 env 由 ci.yml 的 docker-it job 的 badpg service（vanilla postgres:16）提供。
        assertThat(badUrl)
            .as("BAD_DB_DATASOURCE_URL 只在 CI 提供；本机不应跑 docker 组测试")
            .isNotBlank();

        // 必须走命令行参数（run 的可变参数，优先级高于 OS env）：builder.properties() 落在
        // defaultProperties——全链最低优先级，会被 docker-it job 的 SPRING_PROFILES_ACTIVE=docker
        // 与 SPRING_DATASOURCE_URL=<好库> 环境变量整体压掉，导致本测试实际连上好库优雅启动，
        // assertThatThrownBy 扑空（CI 实测）。命令行参数（优先级第 4）压得过 env（第 10）。
        assertThatThrownBy(() -> new SpringApplicationBuilder(AnnonaApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.profiles.active=prod",
                "--annona.kek.secret=dummy-kek-for-test-only-not-empty",
                "--spring.datasource.url=" + badUrl,
                "--spring.datasource.username=" + System.getenv().getOrDefault("BAD_DB_USER", "postgres"),
                "--spring.datasource.password=" + System.getenv().getOrDefault("BAD_DB_PASSWORD", "postgres"),
                "--spring.main.banner-mode=off",
                "--logging.level.root=OFF"))
            // 守卫的 IllegalStateException 发生在 bean 创建阶段，Spring 会把它包成
            // BeanCreationException（不同于 StartupValidator：后者在 context refresh 前抛，
            // 所以 ProdProfileWithoutKekIT 断言的就是裸 IllegalStateException）。
            .isInstanceOf(BeanCreationException.class)
            .hasMessageContaining("Missing required PostgreSQL extensions")
            .hasMessageContaining("pgvector/pgvector:pg16")
            .hasRootCauseInstanceOf(IllegalStateException.class);
    }
}
