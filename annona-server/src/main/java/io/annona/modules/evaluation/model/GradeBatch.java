package io.annona.modules.evaluation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 逐题评估的结构化输出目标（{@code StructuredOutputInvoker} 解析）。{@code grades} 按提交顺序
 * 与批次内的槽位一一对应，{@code index} 是批次内定位（0 起，消费者据此映射回 questionId/
 * followUpIndex）。{@code ignoreUnknown} 容忍模型多给字段（少给/畸形由解析失败走降级）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GradeBatch(List<QuestionGrade> grades) {

    public GradeBatch {
        grades = grades == null ? List.of() : List.copyOf(grades);
    }

    /** 单槽评分：score 0..100；strengths/improvements 文本数组。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuestionGrade(int index, Integer score, String feedback,
                                List<String> strengths, List<String> improvements) {

        public QuestionGrade {
            strengths = strengths == null ? List.of() : List.copyOf(strengths);
            improvements = improvements == null ? List.of() : List.copyOf(improvements);
        }
    }
}
