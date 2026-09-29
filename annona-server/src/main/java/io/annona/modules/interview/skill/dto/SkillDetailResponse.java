package io.annona.modules.interview.skill.dto;

import io.annona.modules.interview.skill.model.SkillDefinition;

/**
 * 技能详情：在列表项之上多给 persona 正文（考法全文，出题口径的人读版本）。
 */
public record SkillDetailResponse(String key, String name, String description, String parent,
                                  String persona, String displayName,
                                  SkillDefinition.Display display) {
}
