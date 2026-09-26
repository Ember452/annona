package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.identity.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 证明 identity 的 JPA 实体在 {@code ddl-auto: validate} 下与真实 V1 schema 匹配
 * （CITEXT / TIMESTAMPTZ 靠 {@code @Column(columnDefinition=...)} 对齐）。
 *
 * <p>context 能在 docker profile（validate 生效）下加载即证明映射无漂移；这里再跑一次
 * count() 触发真实查询坐实。本机不跑；CI docker-it（pgvector service）执行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("identity 实体在真实 PG 上通过 ddl-auto:validate")
class IdentitySchemaValidateIT {

    @Autowired
    private AppUserRepository appUserRepository;

    @Test
    @DisplayName("AppUserRepository 可用且能对 app_user 计数（映射与 schema 一致）")
    void repositoryQueriesAppUser() {
        assertThat(appUserRepository).isNotNull();
        assertThat(appUserRepository.count()).isGreaterThanOrEqualTo(0);
    }
}
