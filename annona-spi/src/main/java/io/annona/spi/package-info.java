/**
 * annona 扩展点契约层（SPI）。
 *
 * <p>本包只放<b>接口 + 跨模块契约 DTO</b>，是唯一允许被外部仓库依赖并实现的技术契约。
 * 五个扩展点：
 * <ul>
 *   <li>{@link io.annona.spi.identity.IdentityProvider}——身份源（local / platform / none）</li>
 *   <li>{@link io.annona.spi.model.ModelProvider}——模型网关（LLM / Embedding / Rerank 的供应商抽象）</li>
 *   <li>{@link io.annona.spi.retrieval.Retriever}——检索后端（默认 PgVector，可插 ES/Milvus）</li>
 *   <li>{@link io.annona.spi.signal.LearningSignalReader}——学习信号读取（planner 的输入侧）</li>
 *   <li>{@link io.annona.spi.planner.DecisionRule}——训练决策规则（可解释面板的理由生成器）</li>
 * </ul>
 *
 * <p>硬性约束（由 annona-server 的 ArchUnit 规则强制）：
 * <ul>
 *   <li>本包内任何类禁止 import {@code org.springframework..}。</li>
 *   <li>禁止 import {@code jakarta.persistence..}。</li>
 *   <li>禁止 import 任何 SDK（AWS、Dashscope、Tika 等）。</li>
 *   <li>允许 import：JDK、{@code io.annona.common} 的纯 Java 部分。</li>
 * </ul>
 *
 * <p>发布策略：{@code annona-spi} 是唯一发到 Maven Central 的 artifact；
 * 第三方贡献者实现新 Retriever / IdentityProvider 时在自己仓库依赖本 artifact，
 * 不进主仓 PR。见 docs/annona-项目结构.md §13.3。
 */
package io.annona.spi;
