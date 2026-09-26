/**
 * direction 方向主数据的唯一属主（direction-master-data-adr）。
 *
 * <p>本包承载两层职责：
 * <ul>
 *   <li><b>字典管理 API</b>——新建（USER_CUSTOM 即时落库）、归档（唯一的"删除"路径，
 *       有历史数据的方向只归档不物理删）、绑定知识库（USER_CUSTOM 单向升级为
 *       KNOWLEDGE_BASE）。主数据被 6 个业务模块消费、不隶属任何单一模块，故管理端点
 *       同落本包（docs/annona-项目结构.md §4 与 ADR 修订记录已同步此例外）。</li>
 *   <li><b>对消费模块的只读访问</b>——study / questionbank / interview 等只能通过
 *       {@link io.annona.shared.direction.service.DirectionQueryService} 读，
 *       禁止直接改字典；业务表方向列一律外键 {@code direction.id}，禁止存 key 字符串。</li>
 * </ul>
 *
 * <p>允许依赖：{@code io.annona.common}、{@code io.annona.spi}、JDK、Spring、JPA。
 * 禁止：依赖 {@code io.annona.modules.*} 或 {@code io.annona.infrastructure.*}。
 *
 * <p>当前内容：{@code DirectionController} / {@code DirectionCommandService} /
 * {@code DirectionQueryService}（P1a-03）。
 */
package io.annona.shared.direction;
