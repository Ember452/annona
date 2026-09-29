package io.annona.modules.interview.skill.dto;

import io.annona.modules.interview.skill.model.SkillDefinition;
import java.util.List;

/**
 * 技能列表项：展示层所需的最小集。displayName/display 可空（前端兜底）；
 * categories 供 P1b-04 组卷配额展示，批 1 多为空。
 */
public record SkillSummaryResponse(String key, String name, String description, String parent,
                                   String displayName, SkillDefinition.Display display,
                                   List<SkillDefinition.Category> categories) {
}
