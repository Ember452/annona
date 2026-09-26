/**
 * study 采集模块（P1a-04）：打卡、番茄钟会话、服务端心跳与质量分级。
 *
 * <p>允许依赖：{@code io.annona.shared.direction.service}（只经 DirectionQueryService
 * 校验方向可见性，ArchUnit 已禁直碰 shared.direction.repository）、{@code io.annona.common}。
 *
 * <p>Redis 心跳时间线的端口 {@code io.annona.common.study.HeartbeatTimeline} 定义在 common
 * （纯 JDK 签名），Redisson 实现在 infrastructure.cache（SessionStore 同款装配）——annona-server
 * 编译期看不到 Redisson（根 pom 决策：Redis 客户端依赖只落在 infrastructure）。
 *
 * <p>{@code quality/} 的判定算法唯一权威定义：docs/specs/2026-09-26-study-collection-adr.md §决策 2。
 */
package io.annona.modules.study;
