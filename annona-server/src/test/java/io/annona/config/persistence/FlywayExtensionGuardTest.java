package io.annona.config.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.config.properties.AnnonaStartupProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

/**
 * Mockito slice 测试（P0-08）：{@link FlywayExtensionGuard} 的 {@code FlywayMigrationStrategy}
 * 在扩展齐全 / 缺失 / 检查关闭 / DB 不可达四种情境下的行为。不依赖真 PG 与 Docker。
 */
@DisplayName("FlywayExtensionGuard 迁移前扩展检查（P0-08）")
class FlywayExtensionGuardTest {

    @Test
    @DisplayName("vector + citext 均已安装 → 不抛，flyway.migrate() 被调")
    void allExtensionsInstalledProceeds() throws SQLException {
        DataSource ds = mockDataSourceWithExtensions("vector", "citext");
        Flyway flyway = mock(Flyway.class);
        FlywayMigrationStrategy strategy = new FlywayExtensionGuard()
            .flywayExtensionGuardStrategy(ds, props(true, true));

        strategy.migrate(flyway);

        verify(flyway, times(1)).migrate();
    }

    @Test
    @DisplayName("缺 vector → 抛错，flyway.migrate() 从不被调用；消息含 pgvector/pgvector:pg16 可执行提示")
    void missingVectorThrows() throws SQLException {
        DataSource ds = mockDataSourceWithExtensions("citext"); // 缺 vector
        Flyway flyway = mock(Flyway.class);
        FlywayMigrationStrategy strategy = new FlywayExtensionGuard()
            .flywayExtensionGuardStrategy(ds, props(true, true));

        assertThatThrownBy(() -> strategy.migrate(flyway))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Missing required PostgreSQL extensions")
            .hasMessageContaining("vector")
            .hasMessageContaining("pgvector/pgvector:pg16");
        verify(flyway, never()).migrate();
    }

    @Test
    @DisplayName("check-pg-extensions=false → 完全跳过查询，直接 migrate（本机 dev/test 场景）")
    void checkDisabledSkipsQuery() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Flyway flyway = mock(Flyway.class);
        FlywayMigrationStrategy strategy = new FlywayExtensionGuard()
            .flywayExtensionGuardStrategy(ds, props(true, false));

        strategy.migrate(flyway);

        verify(ds, never()).getConnection();
        verify(flyway, times(1)).migrate();
    }

    @Test
    @DisplayName("pg_extension 查询本身失败（比如连到非 PG 库）→ 抛错并保留 cause")
    void queryFailureWrappedWithCause() throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("not a postgres"));
        Flyway flyway = mock(Flyway.class);
        FlywayMigrationStrategy strategy = new FlywayExtensionGuard()
            .flywayExtensionGuardStrategy(ds, props(true, true));

        assertThatThrownBy(() -> strategy.migrate(flyway))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pg_extension cannot be read")
            .hasCauseInstanceOf(SQLException.class);
        verify(flyway, never()).migrate();
    }

    private static AnnonaStartupProperties props(boolean requireKek, boolean checkPgExtensions) {
        AnnonaStartupProperties p = new AnnonaStartupProperties();
        p.setRequireKek(requireKek);
        p.setCheckPgExtensions(checkPgExtensions);
        return p;
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
