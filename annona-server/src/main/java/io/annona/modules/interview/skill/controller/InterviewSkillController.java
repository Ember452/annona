package io.annona.modules.interview.skill.controller;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.result.Result;
import io.annona.modules.interview.skill.dto.SkillDetailResponse;
import io.annona.modules.interview.skill.dto.SkillSummaryResponse;
import io.annona.modules.interview.skill.model.SkillDefinition;
import io.annona.modules.interview.skill.service.SkillRegistry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内置技能查询端点（P1b-01）。技能是 classpath 静态资源、全用户共享，只读且无租户数据。
 * 面试创建用 directionId（来自 /api/directions，key 与此处一致）；本端点负责把技能元数据
 * （展示信息/考察维度/persona 考法）交给前端与后续组卷侧。
 */
@RestController
@RequestMapping("/api/interview/skills")
public class InterviewSkillController {

    private final SkillRegistry registry;

    public InterviewSkillController(SkillRegistry registry) {
        this.registry = registry;
    }

    /** GET /api/interview/skills——全部内置技能（key 升序）。 */
    @GetMapping
    public Result<List<SkillSummaryResponse>> list() {
        return Result.success(registry.list().stream()
            .map(this::toSummary)
            .toList());
    }

    /** GET /api/interview/skills/{key}——单个技能详情（含 persona 考法全文）。 */
    @GetMapping("/{key}")
    public Result<SkillDetailResponse> detail(@PathVariable String key) {
        SkillDefinition definition = registry.find(key)
            .orElseThrow(() -> new BusinessException(ErrorCode.SKILL_NOT_FOUND));
        return Result.success(new SkillDetailResponse(definition.key(), definition.name(),
            definition.description(), definition.parent(), definition.persona(),
            definition.meta().displayName(), definition.meta().display()));
    }

    private SkillSummaryResponse toSummary(SkillDefinition definition) {
        return new SkillSummaryResponse(definition.key(), definition.name(), definition.description(),
            definition.parent(), definition.meta().displayName(), definition.meta().display(),
            definition.meta().categories());
    }
}
