/**
 * 五个扩展点的<b>内存实现</b>，专供测试与单机免登录模式。
 *
 * <p>存在的意义（P0 出口条件 ③："五个 SPI 有骨架与 Fake 实现"）：
 * <ul>
 *   <li>业务模块（P1a 起的 {@code io.annona.modules.*}）在写单测时不必启 PG / Redis /
 *       真调模型；{@code @SpringBootTest} 用 {@code @MockBean} 或直接注入本包的
 *       Fake 即可跑通完整装配。</li>
 *   <li>{@code annona.identity.mode=none} 的单机免登录路径可以直接 {@code new
 *       FakeIdentityProvider()} 作为默认 {@code IdentityProvider} bean（identity-credential-storage-adr
 *       §决策 §4 明写"单机模式不另立数据模型"）。</li>
 *   <li>{@code annona.retrieval.backend=fake} 与 {@code annona.model.default=fake}
 *       给离线开发一个能启动、能返回的形状，避免"clone 下来没 Key 起不来"（自部署产品
 *       第一号杀手，见 storage ADR 与 model-api-key ADR 反复强调）。</li>
 * </ul>
 *
 * <p>本包所有类<b>必须</b>只依赖 JDK 与 {@code io.annona.spi}，禁止 Spring /
 * Jakarta Persistence / AWS SDK（ArchUnit 规则 5 强制）。因此 Fake 不带
 * {@code @Component}；调用方按需要在自己的 {@code @Configuration} 里
 * {@code @Bean Fake*()} 或用 {@code @MockBean} 覆盖。
 *
 * <p>与 {@code annona-infrastructure} 的分工：infra 是"真接外部系统"的
 * 实现（PG / Redis / DashScope / S3），fake 是"完全不接"的实现；两者实现同一
 * 组 {@code io.annona.spi} 接口，通过 {@code @ConditionalOnProperty} 切换。
 */
package io.annona.spi.fake;
