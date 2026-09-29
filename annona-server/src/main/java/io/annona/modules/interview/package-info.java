/**
 * interview 面试功能域。
 *
 * <p>当前内容：{@code skill}（SKILL.md 注册表与内置方向清单，P1b-01）。
 * 后续落点：orchestrator（组卷/状态机，P1b-04/05）、evaluation 消费侧（P1b-06/07）——
 * 届时按任务补子包声明，不在本文件预占。
 *
 * <p>允许依赖：{@code io.annona.common}、{@code io.annona.spi}、{@code io.annona.shared.*}
 * 只读（方向经 DirectionQueryService、进度经 shared/progress 信封）；跨模块只读可注入
 * 其他模块的 XxxQueryService（qa → retrieval/knowledge 先例，AGENTS §4 已消歧）；
 * 写路径一律领域事件。禁止依赖 {@code io.annona.infrastructure.*} 与其他模块的内部包。
 */
package io.annona.modules.interview;
