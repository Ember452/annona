package io.annona.config.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

/**
 * Mockito slice 测试（P0-08）：{@link FlywayExtensionGuard} 的 {@code FlywayMigrationStrategy}
 * 在扩展齐全 / 缺失 / 无 DataSource / DB 不可达四种情境下的行为。不依赖真 PG 与 Docker。
 *
 * <p>注意 {@code check-pg-extensions=false} 不再由策略内部判断，而是类上的
 * {@code @ConditionalOnProperty} 决定整个守卫是否装配——那部分由
 * {@code FlywayExtensionGuardWiringIT}（CI 的 docker 组）覆盖，本类只测策略行为本身。
 */
@DisplayName("FlywayExtensionGuard 迁移前扩展检查（P0-08）")
class FlywayExtensionGuardTest {

    @Test
    @DisplayName("vector + citext 均已安装 → 不抛，flyway.migrate() 被调")
    void allExtensionsInstalledProceeds() throws SQLException {
        DataSource ds = mockDataSourceWithExtensions("vector", "citext");
        Flyway flyway = mock(Flyway.class);

        strategy(ds).migrate(flyway);

        verify(flyway, times(1)).migrate();
    }

    @Test
    @DisplayName("缺 vector → 抛错，migrate 从不被调用；消息含 pgvector/pgvector:pg16 可执行提示")
    void missingVectorThrows() throws SQLException {
        DataSource ds = mockDataSourceWithExtensions("citext"); // 缺 vector
        Flyway flyway = mock(Flyway.class);

        assertThatThrownBy(() -> strategy(ds).migrate(flyway))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Missing required PostgreSQL extensions")
            .hasMessageContaining("vector")
            .hasMessageContaining("pgvector/pgvector:pg16");
        verify(flyway, never()).migrate();
    }

    @Test
    @DisplayName("无 DataSource（test profile 排除 DB autoconfig）→ 跳过检查，直接 migrate")
    void absentDataSourceSkipsCheck() {
        Flyway flyway = mock(Flyway.class);

        strategy(null).migrate(flyway);

        verify(flyway, times(1)).migrate();
    }

    @Test
    @DisplayName("pg_extension 查询本身失败（比如连到非 PG 库）→ 抛错并保留 cause")
    void queryFailureWrappedWithCause() throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("not a postgres"));
        Flyway flyway = mock(Flyway.class);

        assertThatThrownBy(() -> strategy(ds).migrate(flyway))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pg_extension cannot be read")
            .hasCauseInstanceOf(SQLException.class);
        verify(flyway, never()).migrate();
    }

    /**
     * 包装成守卫所需的 {@code Optional}；传 null 表示容器里没有 DataSource（空 Optional）。
     */
    private static FlywayMigrationStrategy strategy(DataSource ds) {
        return new FlywayExtensionGuard()
            .flywayExtensionGuardStrategy(Optional.ofNullable(ds));
    }

    /**
     * 造一个 mock DataSource：连接上执行任意 SQL 都返回给定 {@code installed} 数组作 ResultSet。
     * 每次调用创建独立游标（{@link AtomicInteger}），互不污染。
     */
    private static DataSource mockDataSourceWithExtensions(String... installed) throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        AtomicInteger cursor = new AtomicInteger(-1);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenAnswer(inv -> cursor.incrementAndGet() < installed.length);
        when(rs.getString(1)).thenAnswer(inv -> installed[cursor.get()]);
        return ds;
    }
}
