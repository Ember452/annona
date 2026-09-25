package io.annona.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 启动期检查的开关（{@code annona.startup.*}）。
 *
 * <p>两个布尔量各自门控 P0-08 的两类 fail-fast 检查：
 * <ul>
 *   <li>{@code require-kek}——决定 {@link io.annona.bootstrap.StartupValidator} 是否
 *       在 {@code ApplicationEnvironmentPreparedEvent} 上检查 {@code annona.kek.secret}；
 *       默认 {@code true}（安全侧默认），dev profile 显式关。</li>
 *   <li>{@code check-pg-extensions}——决定 {@link io.annona.config.persistence.FlywayExtensionGuard}
 *       是否在 {@code FlywayMigrationStrategy.migrate()} 之前查 {@code pg_extension}；
 *       默认 {@code true}，dev 与 test profile 显式关（本机没 PG 时不该阻塞启动）。</li>
 * </ul>
 *
 * <p>prod profile 里两者都开（{@code application-prod.yaml} 里显式写 true 而不是依赖默认，
 * 让 review 一眼能看见"这个 profile 会 fail-fast"）；{@code application-dev.yaml} 与
 * {@code application-test.yaml} 都写 false。
 */
@ConfigurationProperties(prefix = "annona.startup")
public class AnnonaStartupProperties {

    private boolean requireKek = true;
    private boolean checkPgExtensions = true;

    public boolean isRequireKek() {
        return requireKek;
    }

    public void setRequireKek(boolean requireKek) {
        this.requireKek = requireKek;
    }

    public boolean isCheckPgExtensions() {
        return checkPgExtensions;
    }

    public void setCheckPgExtensions(boolean checkPgExtensions) {
        this.checkPgExtensions = checkPgExtensions;
    }
}
