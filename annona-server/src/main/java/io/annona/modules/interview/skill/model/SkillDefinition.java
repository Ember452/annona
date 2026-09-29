package io.annona.modules.interview.skill.model;

import java.util.List;

/**
 * 一个内置技能的完整定义（SKILL.md front-matter + persona 正文 + skill.meta.yml 的解析产物）。
 *
 * <p>{@code key} 即内置 direction 的 key（全小写-dashed，direction-master-data-adr 的 key 口径）；
 * {@code persona} 是 front-matter 之后的 Markdown 正文，承载该方向的考察范围、难度口径与评分侧重
 * （设计文档 §91："一个方向 = 一份 SKILL.md"）。记录不可变，注册表加载完成后只读。
 */
public record SkillDefinition(String key, String name, String description, String parent,
                              String persona, SkillMeta meta) {

    /** skill.meta.yml 的展示元数据；文件缺席时 {@link #empty()}，displayName 由前端用 name 兜底。 */
    public record SkillMeta(String displayName, Display display, List<Category> categories) {

        public static SkillMeta empty() {
            return new SkillMeta(null, null, List.of());
        }
    }

    /** 前端卡片展示用；全部字段可空，前端自行兜底默认样式（借上游"展示元数据随 API 下发"的形态）。 */
    public record Display(String icon, String gradient, String iconBg, String iconColor) {
    }

    /**
     * 考察维度（P1b-04 组卷配额的输入）。{@code priority} 取 ALWAYS_ONE（每场必考一题）|
     * CORE（优先多出）| NORMAL；{@code ref} 是参考知识文件名，{@code shared=true} 表示优先
     * 从 skills/_shared/references/ 公共池解析。缺省优先级为 NORMAL。
     */
    public record Category(String key, String label, String priority, String ref, boolean shared) {
    }
}
