package io.annona.config.persistence;

import io.annona.config.properties.AnnonaStartupProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway 迁移前的 PG 扩展存在性检查（P0-08）。
 *
 * <p>为什么放在 {@code FlywayMigrationStrategy} 里而不是 {@code StartupValidator}：
 * <ul>
 *   <li>扩展检查需要 {@link DataSource}；{@code StartupValidator} 跑在
 *       {@code ApplicationEnvironmentPreparedEvent}，此时 DataSource 还没装配；</li>
 *   <li>迁移前的语义与 Flyway 天然耦合（V1 里就有 {@code CREATE EXTENSION vector}），
 *       把可读错误放在这个时机最合适；</li>
 *   <li><b>为什么不用 {@code @ConditionalOnBean(DataSource.class)}</b>：该条件只应用于
 *       自动配置类；普通 {@code @Configuration} 的求值早于 {@code DataSourceAutoConfiguration}
 *       注册 bean 定义，结果会是“条件永远为假 → 守卫永远不装配”，而且单测直测
 *       静态方法看不出这个失效（已踩过，见阶段总结 D18 同族问题）。改用
 *       {@code @ConditionalOnClass} + {@code @ConditionalOnProperty} + {@code Optional<DataSource>}：
 *       无 DataSource 时守卫仍装配，但跳过检查（本机 test profile 排除 DB autoconfig）。</li>
 * </ul>
 *
 * <p>错误消息给出<b>可执行动作</b>（P0-08 验收："缺 pgvector/citext 扩展时给出可执行提示"），
 * 而不是 {@code CREATE EXTENSION} SQL 抛的 "extension 'vector' is not available"——用户
 * 看到那句往往不知道该换镜像还是装扩展。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnnonaStartupProperties.class)
@ConditionalOnClass(Flyway.class)
@ConditionalOnProperty(prefix = "annona.startup", name = "check-pg-extensions", havingValue = "true", matchIfMissing = true)
public class FlywayExtensionGuard {

    private static final Logger log = LoggerFactory.getLogger(FlywayExtensionGuard.class);
    private static final List<String> REQUIRED_EXTENSIONS = List.of("vector", "citext");

    @Bean
    FlywayMigrationStrategy flywayExtensionGuardStrategy(Optional<DataSource> dataSource) {
        return flyway -> {
            // 用 Optional 而非 ObjectProvider（后者不是函数接口，测试里造桩要写一堆方法）：
            // 在没有 DataSource 的 profile（如 test）下为空，不能强行创建连接
            if (dataSource.isPresent()) {
                requireExtensions(dataSource.get());
            } else {
                log.debug("No DataSource available - PG extension check skipped");
            }
            flyway.migrate();
        };
    }

    /**
     * 用一次预编译 {@code SELECT ... WHERE extname IN (?, ?)} 拿到已安装扩展集合，
     * 缺失即抛错（不循环查库，AGENTS.md §Never Do "不要循环调用 DB"）。
     */
    private static void requireExtensions(DataSource ds) {
        Set<String> installed = new HashSet<>();
        String sql = "SELECT extname FROM pg_extension WHERE extname IN (?, ?)";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, REQUIRED_EXTENSIONS.get(0));
            ps.setString(2, REQUIRED_EXTENSIONS.get(1));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    installed.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "annona requires PostgreSQL 16 with pgvector + citext, but pg_extension cannot be read.\n"
                    + "Confirm the datasource is a PostgreSQL instance and the account has SELECT on pg_extension.\n"
                    + "Root cause: " + e.getMessage(), e);
        }
        List<String> missing = new ArrayList<>(REQUIRED_EXTENSIONS);
        missing.removeAll(installed);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "Missing required PostgreSQL extensions: " + missing + ".\n"
                    + "annona requires PostgreSQL 16 with pgvector. Options:\n"
                    + "  (a) Docker: use image pgvector/pgvector:pg16 (NOT postgres:16)\n"
                    + "  (b) Native: install postgresql-16-pgvector + postgresql-contrib\n"
                    + "  (c) Managed cloud: enable pgvector via the provider's extension console\n"
                    + "See README §Prerequisites.");
        }
        log.info("PG extension check passed: installed={}", installed);
    }
}
