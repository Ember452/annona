/**
 * {@code @ConfigurationProperties} 集中地：每个业务域一个 Properties 类，禁止散落 {@code @Value}。
 *
 * <p>本包存在的意义：AGENTS.md §Config And Properties 明写"不要在 Service 中散落
 * {@code @Value}"——所有外部配置以类型化的 Properties 呈现，配合 IDE 自动补全与
 * 启动期 binding 校验（{@code @Validated} 时可加 Jakarta Validation）。
 *
 * <p>P0-05 与 P0-08 引入的两个 Properties：
 * <ul>
 *   <li>{@code AnnonaStartupProperties}（{@code annona.startup.*}）：KEK 检查与 PG 扩展检查
 *       的 profile-conditional 开关。见 commit 4 (P0-08)。</li>
 * </ul>
 *
 * <p>注意：{@code AnnonaThreadProperties}（{@code annona.thread-pool.*}）住在
 * {@code io.annona.config.async} 包，与它的<b>唯一</b>消费者 {@code ThreadPoolConfig}
 * 同包，避免"properties 与使用者被物理隔离导致改配置忘了改代码"。这条判断也适用于
 * 未来 {@code AnnonaRetrievalProperties}（住在 {@code modules.retrieval.provider}）等。
 *
 * <p>{@code annona.kek.secret} 目前直接由 {@code Environment.getProperty} 读取，
 * 不建 {@code AnnonaKekProperties}——单一字段没必要建一个类；等 P1b-10 引入
 * {@code kek_version} 轮换上下文时再抽出。
 */
package io.annona.config.properties;
