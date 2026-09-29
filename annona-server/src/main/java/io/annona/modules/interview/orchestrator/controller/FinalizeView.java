package io.annona.modules.interview.orchestrator.controller;

/**
 * 交卷结果视图（批 2 只回落库事实——评分与报告在批 3，占位文案由前端呈现）。
 *
 * @param id               会话 ID
 * @param answeredCount    落终态的槽数（含弃答占位）
 * @param status           恒为 COMPLETED（终态才回视图，失败走 Result.error）
 * @param evaluatorVersion 评估器版本留痕（幂等键另一半）
 */
public record FinalizeView(String id, int answeredCount, String status, String evaluatorVersion) {
}
