package io.annona.shared.direction.service;

import java.util.List;

/**
 * 内置方向清单的来源端口（skill-questionbank-adr §决策 2）。
 *
 * <p>接口定义在本包、由 {@code modules/interview/skill} 的注册表实现——shared 不得依赖
 * modules，依赖倒转靠本接口 + {@code ObjectProvider} 完成：播种器只认接口，无实现 bean 时
 * （最小上下文/单测）静默跳过播种。实现方保证清单与 classpath 技能目录一致，"目录放一个
 * SKILL.md 即被识别"由它兑现。
 */
public interface SkillDirectionCatalog {

    List<BuiltinDirectionSpec> builtinDirections();

    /**
     * @param key 全小写-dashed，落 direction.key（owner = NULL 命名空间）
     * @param name 展示名，落 direction.name
     */
    record BuiltinDirectionSpec(String key, String name) {
    }
}
