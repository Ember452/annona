/**
 * 面试编排（P1b-04/05）：组卷计划（plan）、题目装配（pack，T4 落）、会话状态机（session）与
 * 冷热分层快照（cache，T5 落）。
 *
 * <p>允许依赖：{@code io.annona.common}、{@code io.annona.spi}、{@code io.annona.shared.*}
 * 只读、questionbank 的 {@code QuestionQueryService}（跨模块只读端口，AGENTS §4 例外①，
 * qa → retrieval/knowledge 先例）。禁止 import 其他模块内部包；对 planner/advisor 的调用
 * 走既有白名单（orchestrator → planner，ADR），批 2 静态计划不触碰。
 */
package io.annona.modules.interview.orchestrator;
