/**
 * 题库跨模块读模型（shared "只放契约与读模型"口径）：interview 组卷经
 * {@link QuestionQueryService} 只读消费 questionbank 的题目池。
 *
 * <p>为什么端口在 shared 而不是直接 import questionbank 的 Service（2026-09-29 解环）：
 * 批 1 的 questionbank→interview.skill（出题读 SKILL.md）已存在，interview→questionbank
 * 直接成环会被 ArchUnit slice-free-of-cycles 拒绝；shared 读模型是 AGENTS §4 与
 * shared/package-info 本就写明的第二条合法路径，两侧都只指向 shared，环消失且方向可证。
 *
 * <p>允许依赖：JDK 与 {@code io.annona.common}；实现方在 {@code io.annona.modules.questionbank}。
 */
package io.annona.shared.question;
