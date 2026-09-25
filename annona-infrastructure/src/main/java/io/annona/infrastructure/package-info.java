/**
 * annona 技术实现层根包。
 *
 * <p>职责：SPI 接口的<b>具体实现</b>与外部系统适配。P1a 起逐步落入：
 * <ul>
 *   <li>{@code persistence/}——PostgreSQL + pgvector 类型注册、Repository 支持</li>
 *   <li>{@code cache/}——Redis / Redisson 客户端与 Stream 模板</li>
 *   <li>{@code storage/}——S3 / RustFS 客户端</li>
 *   <li>{@code llm/}——{@code ModelProvider} 各家实现（DashScope 兼容 OpenAI / 本地推理）</li>
 *   <li>{@code retrieval/}——{@code PgVectorRetriever} 与将来 {@code EsRetriever}</li>
 *   <li>{@code parse/}——Tika 文档解析适配</li>
 *   <li>{@code export/}——iText PDF 导出</li>
 *   <li>{@code crypto/}——API Key 加解密（AES/GCM + KEK 轮换）</li>
 * </ul>
 *
 * <p>P0-02 阶段本模块只有 pom 与包骨架，无源码；P1a-01 起补齐 {@code identity} 的 SPI 实现。
 *
 * <p>禁止：包含业务规则（那属于 {@code modules/*}）；被 {@code annona-server} 编译期直接引用具体类
 * （依赖方向是 runtime-only，见 docs/annona-项目结构.md §3）。
 */
package io.annona.infrastructure;
