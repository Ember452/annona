package io.annona.modules.interview.orchestrator.pack;

import java.util.List;
import java.util.UUID;

/**
 * 组卷结果。
 *
 * @param questionIds    命中的主问题 ID（按槽位顺序；池不足时短于 totalCount——宁缺毋滥）
 * @param skippedReasons 缺口说明（面向用户的可读文案，进 SessionView 与面试中心提示）
 */
public record PackResult(List<UUID> questionIds, List<String> skippedReasons) {
}
