package io.annona.modules.resume.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 简历 AI 分析结果（{@code resume.analysis} JSONB，同时是 {@code StructuredOutputInvoker}
 * 的结构化输出目标）。{@code ignoreUnknown} 容忍模型多给字段；缺字段/畸形由解析失败走降级。
 *
 * @param summary  一句话画像
 * @param strengths 亮点（经验深度、技能匹配等）
 * @param concerns  关注点/风险（空窗、跳槽频繁、技能断层等）
 * @param skills   识别到的技术方向关键词（可映射 direction）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResumeAnalysis(String summary, List<String> strengths, List<String> concerns,
                             List<String> skills) {

    public ResumeAnalysis {
        strengths = strengths == null ? List.of() : List.copyOf(strengths);
        concerns = concerns == null ? List.of() : List.copyOf(concerns);
        skills = skills == null ? List.of() : List.copyOf(skills);
    }
}
