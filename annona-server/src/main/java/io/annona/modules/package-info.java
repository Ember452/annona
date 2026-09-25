/**
 * annona 业务模块根包。每个子包（{@code identity / study / plan / knowledge / retrieval / qa /
 * questionbank / interview / evaluation / planner / voice / schedule / agent / usage / resume / notify}）
 * 自包含一整套 MVC 分层，见 docs/annona-项目结构.md §4-§5。
 *
 * <p>依赖规则（ArchUnit 强制，违规即 CI 失败）：
 * <ul>
 *   <li>{@code io.annona.modules..} 禁止依赖 {@code io.annona.infrastructure..}（编译期不可见实现类）。</li>
 *   <li>{@code io.annona.modules.<a>..} 禁止依赖 {@code io.annona.modules.<b>..}
 *       （唯一白名单：{@code interview/orchestrator -> planner/advisor}）。</li>
 *   <li>{@code io.annona.modules.planner..} 禁止依赖 {@code interview / voice / schedule}
 *       （只能被它们调用或读 shared 信号）。</li>
 * </ul>
 *
 * <p>跨模块通信只有两种方式：只读走 {@code XxxQueryService} 或 {@code shared} 读模型；写走领域事件。
 *
 * <p>P0-07 阶段本包只落顶层骨架与 package-info；具体业务子包按 P1a/P1b/P1c 计划逐个建立。
 */
package io.annona.modules;
