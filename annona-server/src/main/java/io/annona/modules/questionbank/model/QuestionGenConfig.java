package io.annona.modules.questionbank.model;

/**
 * 出题参数快照（qb_generation_task.config JSONB）：请求原样冻结，消费侧按快照执行、
 * 前端读回"目标追问数"与题目上的实际追问数对比出缺口徽章（借 🅖 的目标/实际对比口径）。
 */
public record QuestionGenConfig(int difficulty, int questionCount, int followUpCount) {
}
