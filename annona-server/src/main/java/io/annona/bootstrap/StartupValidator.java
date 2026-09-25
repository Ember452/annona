package io.annona.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * 启动期 KEK（Key Encryption Key）存在性校验（P0-08）。
 *
 * <p>为什么用 {@code ApplicationEnvironmentPreparedEvent} 而不是 {@code @PostConstruct}：
 * 后者时机与 {@code FlywayAutoConfiguration} 顺序不确定，可能出现"KEK 缺失但 Flyway 已经跑了
 * V1 迁移"的坏状态——迁移数据一旦用了错的（或缺失的）主密钥加密，事后补配真 KEK 会导致存量密文
 * 全部解不开，参见 docs/specs/2026-09-25-model-api-key-adr.md §背景 与 §否决的备选 第 2 行
 * （静默 {@code DEV_FALLBACK_KEY}）。前者由 Spring Boot 的 {@code SpringApplication.run()}
 * 在 Environment 准备好、Context 尚未刷新时触发，早于所有 Bean 装配。
 *
 * <p>注册方式：{@code META-INF/spring.factories} 的 {@code ApplicationListener} 键。
 * 不用 {@code @Component} 是因为 {@code @Component} 只在 context refresh 之后被扫到，
 * 错过了 {@code ApplicationEnvironmentPreparedEvent}。
 *
 * <p>触发条件：{@code annona.startup.require-kek=true}（默认）时校验
 * {@code annona.kek.secret} 必须是非空且不是 {@value #DEV_FALLBACK_PLACEHOLDER} 的字符串；
 * 否则直接抛 {@link IllegalStateException} 终止启动。
 */
public class StartupValidator implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);

    /** {@code application-dev.yaml} 里 dev-only fallback 的占位值；prod profile 若不小心继承了这个值，本类会拒绝。 */
    static final String DEV_FALLBACK_PLACEHOLDER = "dev-only-fallback-do-not-use-in-prod";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        validate(event.getEnvironment());
    }

    /**
     * 提取成 static 便于纯逻辑单测（无需构造 Spring 事件）。
     *
     * @throws IllegalStateException 当 {@code require-kek=true} 但 KEK 缺失或为 dev fallback 常量时
     */
    static void validate(Environment env) {
        boolean requireKek = env.getProperty("annona.startup.require-kek", Boolean.class, Boolean.TRUE);
        if (!requireKek) {
            log.debug("KEK check skipped (annona.startup.require-kek=false)");
            return;
        }
        String kek = env.getProperty("annona.kek.secret", "");
        if (kek.isBlank()) {
            throw new IllegalStateException(
                "ANNONA_SECRET_KEY is required (annona.startup.require-kek=true) but was not provided.\n"
                    + "Generate one with:  openssl rand -base64 32\n"
                    + "See docs/specs/2026-09-25-model-api-key-adr.md for KEK handling rules.");
        }
        if (DEV_FALLBACK_PLACEHOLDER.equals(kek)) {
            throw new IllegalStateException(
                "ANNONA_SECRET_KEY equals the dev-only fallback placeholder in a profile that requires KEK.\n"
                    + "Real deployment must generate one with:  openssl rand -base64 32\n"
                    + "See docs/specs/2026-09-25-model-api-key-adr.md §3 (禁止静默 fallback).");
        }
        log.info("KEK presence check passed");
    }
}
