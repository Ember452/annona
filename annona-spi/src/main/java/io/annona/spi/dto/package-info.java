/**
 * 跨模块契约 DTO 集中放本包。放这里的条件：<b>至少被两个业务模块读写</b>，
 * 或是 SPI 接口的出入参；否则留在业务模块自己的 {@code dto} 包。
 *
 * <p>本包内所有类型必须是 record 或 interface，禁止引入 Spring / Jakarta Persistence / SDK。
 */
package io.annona.spi.dto;
