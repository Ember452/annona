/**
 * 持久层装配：JPA / 事务 / 审计字段 / pgvector 类型注册 / Flyway 迁移策略。
 *
 * <p>P0-05 阶段本包<b>仅有 package-info</b>；Flyway 迁移前的"扩展存在性检查"作为
 * {@code @Bean FlywayMigrationStrategy} 在 commit 4（P0-08）落地（见
 * {@code io.annona.config.persistence.FlywayExtensionGuard}）。
 *
 * <p>未来落地点：
 * <ul>
 *   <li>P1a-01：{@code PasswordEncoder} bean（scrypt）</li>
 *   <li>P1a-05：{@code HibernateTypesContributor} 注册 pgvector {@code vector(1024)} 列类型</li>
 *   <li>P1a-05：JPA Auditing {@code @EnableJpaAuditing} 与 {@code @CreatedBy} 上下文</li>
 *   <li>P1a-07：{@code TransactionTemplate} 与 {@code @Transactional} 传播策略约束</li>
 * </ul>
 *
 * <p>依赖方向：可引用 {@code javax.sql.DataSource}、Flyway、JPA API；禁止引用
 * {@code io.annona.modules..}（配置层不认识业务）。
 */
package io.annona.config.persistence;
