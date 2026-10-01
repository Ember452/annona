/**
 * {@code LearningSignalReader} 的门面与信号快照模型（shared/package-info 预告的位置）。
 * {@link SignalFacade} 组合 study 与 evaluation 两个只读端口，产出 planner 的方向感知快照。
 *
 * <p>门面是"读模型组合"而非业务规则（阈值/加权都在 planner），因此落在 shared 不违背
 * "shared 不放业务规则"；它实现 SPI 的 {@code LearningSignalReader}（shared 允许依赖 spi）。
 */
package io.annona.shared.signal;
