package io.annona.config.persistence;

import io.annona.config.properties.AnnonaStartupProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 *   <li>{@code @ConditionalOnBean(DataSource.class)}：本机 test profile 排除 DataSource
 *       autoconfig 时本 Bean 不装配，避免"没数据库连不上"的失败噪音。</li>
 * </ul>
 *
 * <p>错误消息给出<b>可执行动作</b>（P0-08 验收："缺 pgvector/citext 扩展时给出可执行提示"），
 * 而不是 {@code CREATE EXTENSION} SQL 抛的 "extension 'vector' is not available"——用户
 * 看到那句往往不知道该换镜像还是装扩展。
 */
@Configuration
@EnableConfigurationProperties(AnnonaStartupProperties.class)
@ConditionalOnBean(DataSource.class)
public class FlywayExtensionGuard {

    private static final Logger log = LoggerFactory.getLogger(FlywayExtensionGuard.class);
    private static final List<String> REQUIRED_EXTENSIONS = List.of("vector", "citext");

    @Bean
    FlywayMigrationStrategy flywayExtensionGuardStrategy(DataSource ds, AnnonaStartupProperties props) {
        return flyway -> {
            if (props.isCheckPgExtensions()) {
                requireExtensions(ds);
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
