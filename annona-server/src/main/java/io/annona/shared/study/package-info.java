/**
 * 学习时长跨模块读模型（shared "只放契约与读模型"口径）：shared/signal 门面经
 * {@link StudySignalPort} 只读消费 study 的质量分级时长聚合。
 *
 * <p>端口在 shared 而非门面直接 import study 的 Service：AGENTS §4 依赖方向
 * modules → spi → common、shared 禁依赖 modules；实现方在 {@code io.annona.modules.study}。
 */
package io.annona.shared.study;
