package io.annona.shared.direction.service;

import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.repository.DirectionRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内置方向播种器（skill-questionbank-adr §决策 2）：启动时把技能注册表的清单幂等写入
 * direction 表（origin=SKILL_BUILTIN、user_id NULL）。
 *
 * <p>幂等口径：key 已存在（任何 origin/任何状态）一律跳过——用户归档过的内置方向不复活，
 * 名称改动不回写（表内数据以首次播种为准，避免每次启动改写用户可见文案）。并发窗口由
 * uq_direction_owner_key（user_id NULL 命名空间）兜底，撞约束视为已存在。
 * 全部插入在一个事务里：半播状态比全不播更难排查。
 *
 * <p>{@code ApplicationRunner} 会被启动流程强制提前实例化，而 test profile（无 DB 冒烟）
 * 排除了 JPA——与 knowledge 后台机器同款机检：门控 + test profile 显式关闭
 * （门控 bean 不得被常驻 bean 硬注入，注入一律走 ObjectProvider）。
 */
@Component
@ConditionalOnProperty(prefix = "annona.direction.seed", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class BuiltinDirectionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BuiltinDirectionSeeder.class);

    private final DirectionRepository repository;
    private final ObjectProvider<SkillDirectionCatalog> catalogProvider;

    public BuiltinDirectionSeeder(DirectionRepository repository,
                                  ObjectProvider<SkillDirectionCatalog> catalogProvider) {
        this.repository = repository;
        this.catalogProvider = catalogProvider;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        SkillDirectionCatalog catalog = catalogProvider.getIfAvailable();
        if (catalog == null) {
            log.info("无 SkillDirectionCatalog 实现，跳过内置方向播种");
            return;
        }
        List<SkillDirectionCatalog.BuiltinDirectionSpec> specs = catalog.builtinDirections();
        int seeded = 0;
        for (SkillDirectionCatalog.BuiltinDirectionSpec spec : specs) {
            if (repository.existsByUserIdIsNullAndKey(spec.key())) {
                continue;
            }
            DirectionEntity entity = new DirectionEntity();
            entity.setId(UUID.randomUUID());
            entity.setKey(spec.key());
            entity.setName(spec.name());
            entity.setOrigin(DirectionEntity.ORIGIN_SKILL_BUILTIN);
            entity.setStatus(DirectionEntity.STATUS_ACTIVE);
            entity.setUserId(null);
            repository.save(entity);
            seeded++;
        }
        if (seeded > 0) {
            log.info("内置方向播种完成：新增 {} 个（清单 {} 个）", seeded, specs.size());
        }
    }
}
