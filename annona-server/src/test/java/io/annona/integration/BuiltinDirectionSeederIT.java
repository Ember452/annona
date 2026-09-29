package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.repository.DirectionRepository;
import io.annona.shared.direction.service.BuiltinDirectionSeeder;
import io.annona.shared.direction.service.SkillDirectionCatalog;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * P1b-01 闭环证据（真 PG，CI docker-it 专属——本机无 PG 跑不了）：
 * 技能注册表 → 播种 → direction 表 → findVisibleActive（前端方向选择器的数据源）。
 * 播种器是 ApplicationRunner，docker profile 下 context 启动即已执行；本 IT 复验
 * 幂等口径（重跑不增不改）与"归档不复活"，并把"内置方向对任意用户可见"钉进真库。
 * @Transactional 仅为归档操作可回滚，不残留数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Transactional
@Tag("docker")
@DisplayName("内置方向播种 → direction 表 → 选择器可见（P1b-01 闭环）")
class BuiltinDirectionSeederIT {

    @Autowired
    private DirectionRepository directionRepository;

    @Autowired
    private BuiltinDirectionSeeder seeder;

    @Autowired
    private SkillDirectionCatalog catalog;

    @Test
    @DisplayName("启动播种已把注册表全量落库（SKILL_BUILTIN）；重跑幂等；对任意用户可见")
    void seededRowsAreVisibleToAnyUser() {
        List<String> catalogKeys = catalog.builtinDirections().stream()
            .map(SkillDirectionCatalog.BuiltinDirectionSpec::key)
            .toList();
        assertThat(catalogKeys).isNotEmpty();

        List<DirectionEntity> builtins = directionRepository.findAll().stream()
            .filter(entity -> DirectionEntity.ORIGIN_SKILL_BUILTIN.equals(entity.getOrigin()))
            .toList();
        // 启动播种落库且与注册表清单一一对应——"目录放一个 SKILL.md 即被识别"的真库证据
        assertThat(builtins).extracting(DirectionEntity::getKey)
            .containsExactlyInAnyOrderElementsOf(catalogKeys);
        assertThat(builtins).allSatisfy(entity -> {
            assertThat(entity.getStatus()).isEqualTo(DirectionEntity.STATUS_ACTIVE);
            assertThat(entity.getUserId()).isNull();
        });

        // 重跑幂等：不新增、不改名
        seeder.run(null);
        assertThat(directionRepository.findAll().stream()
            .filter(entity -> DirectionEntity.ORIGIN_SKILL_BUILTIN.equals(entity.getOrigin()))
            .count()).isEqualTo(builtins.size());

        // 选择器链路闭环：listVisible（GET /api/directions 的数据源）对任意登录用户含全部内置方向
        List<String> visibleKeys = directionRepository.findVisibleActive(UUID.randomUUID())
            .stream().map(DirectionEntity::getKey).toList();
        assertThat(visibleKeys).containsAll(catalogKeys);
    }

    @Test
    @DisplayName("用户归档过的内置方向不复活，且不再出现在可见列表")
    void archivedBuiltinStaysArchived() {
        DirectionEntity victim = directionRepository.findAll().stream()
            .filter(entity -> DirectionEntity.ORIGIN_SKILL_BUILTIN.equals(entity.getOrigin()))
            .findFirst().orElseThrow();
        victim.setStatus(DirectionEntity.STATUS_ARCHIVED);
        directionRepository.saveAndFlush(victim);

        seeder.run(null);
        DirectionEntity reloaded = directionRepository.findById(victim.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DirectionEntity.STATUS_ARCHIVED);
        assertThat(directionRepository.findVisibleActive(UUID.randomUUID()))
            .extracting(DirectionEntity::getId).doesNotContain(victim.getId());
    }
}
