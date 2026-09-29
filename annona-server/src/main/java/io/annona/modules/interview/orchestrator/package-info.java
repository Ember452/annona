/**
 * 面试编排（P1b-04/05）：组卷计划（plan）、题目装配（pack，T4 落）、会话状态机（session）与
 * 冷热分层快照（cache，T5 落）。
 *
 * <p>允许依赖：{@code io.annona.common}、{@code io.annona.spi}、{@code io.annona.shared.*}
 * 只读（方向经 DirectionQueryService、题目池经 shared.question.QuestionQueryService——
 * 读模型端口放 shared 而非直 import questionbank，因批 1 已存在
 * questionbank→interview.skill，直接成环会被 ArchUnit slice-free-of-cycles 拒绝，
 * shared/question/package-info 有全记录）。禁止 import 其他模块内部包；对 planner/advisor 的调用
 * 走既有白名单（orchestrator → planner，ADR），批 2 静态计划不触碰。
 */
package io.annona.modules.interview.orchestrator;
