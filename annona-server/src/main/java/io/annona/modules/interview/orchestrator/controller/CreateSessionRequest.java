package io.annona.modules.interview.orchestrator.controller;

import java.util.List;

/**
 * POST /api/interview/sessions 请求体（平铺字段：构造校验集中在 Facade 组 InterviewPlan，
 * 不靠 Jackson 绑定 compact constructor 抛错——那会把 1001 变成不可控的反序列化报错）。
 *
 * @param directionId   目标方向 ID
 * @param totalCount    主问题数 [1,20]
 * @param difficulties  逐槽难度 1–5，长度须等于 totalCount
 * @param followUpDepth 每题追问层数 [0,3]
 */
public record CreateSessionRequest(String directionId, int totalCount,
                                   List<Integer> difficulties, int followUpDepth) {
}
