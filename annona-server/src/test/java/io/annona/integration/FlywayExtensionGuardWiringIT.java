package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.config.persistence.FlywayExtensionGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * 守卫<b>是否真的装配进容器</b>的断言（2026-09-26 评审整改，阶段总结 D18/D19 的防线）。
 *
 * <p>为什么必须有这个测试：原来的 {@code FlywayExtensionGuard} 用
 * {@code @ConditionalOnBean(DataSource.class)} 挂在普通 {@code @Configuration} 上，
 * 条件永远为假 → 守卫<b>从未装配过</b>，而 {@code FlywayExtensionGuardTest} 直接
 * {@code new} 出对象测策略逻辑，完全看不见这个失效，CI 一路绿灯。
 * 只有从"容器里有没有这个 bean"这一层断言，才能挡住同类回归。
 *
 * <p>{@code @Tag("docker")}：需要真 PG 与 Flyway 自动配置，本机不跑；
 * 由 CI 的 {@code docker-it} job（{@code services: pgvector/pgvector:pg16}）执行。
 * 该 job 现在带"Tests run 非零"断言，所以本用例不跑就会让 CI 变红（D18 的兜底）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("FlywayExtensionGuard 必须真的装配进容器（D18 防线）")
class FlywayExtensionGuardWiringIT {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("docker profile 下容器里存在 FlywayMigrationStrategy，且来自本守卫")
    void guardBeanIsRegistered() {
        String[] beanNames = context.getBeanNamesForType(FlywayMigrationStrategy.class);

        assertThat(beanNames)
            .as("守卫的 FlywayMigrationStrategy 必须装配；若为空，说明条件注解又失效了"
                + "（@ConditionalOnBean 不能用在普通 @Configuration 上）")
            .isNotEmpty();
        assertThat(context.getBean(FlywayExtensionGuard.class))
            .as("策略 bean 必须来自 FlywayExtensionGuard 这个配置类")
            .isNotNull();
    }
}
