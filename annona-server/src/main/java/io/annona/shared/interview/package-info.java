/**
 * 面试会话的跨模块只读模型（shared "只放契约与读模型"口径）。
 *
 * <p>evaluation 模块需要读 interview 的作答行做评估，但不能直接 import interview 的仓储/服务
 * （§4：跨模块只读走 shared 读模型，写走事件——本包的读端口正属前者）。实现方在
 * {@code io.annona.modules.interview}。
 *
 * <p>允许依赖：JDK 与 {@code io.annona.common}；禁止依赖 {@code io.annona.modules.*}。
 */
package io.annona.shared.interview;
