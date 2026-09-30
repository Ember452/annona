/**
 * 跨模块领域事件契约（shared "只放契约与读模型"口径，见 {@code io.annona.shared} 包注释：
 * domain/ 子包专门放领域事件）。
 *
 * <p>AGENTS §4"写路径一律领域事件"：模块之间不得直接 import 彼此的写服务；一个模块的状态
 * 推进需要触发另一模块的副作用时，由前者发布本包定义的事件、后者监听。interview→evaluation
 * 的"交卷即评估"即走 {@link InterviewFinalizedEvent}（interview 发、evaluation 监听），
 * 两侧都只指向 shared，无跨模块写依赖、不成环。
 *
 * <p>允许依赖：JDK（事件是不可变 record，不引框架类型；Spring 的 {@code ApplicationEventPublisher}
 * 由发布方模块自己持有，本包不放）。实现/监听方在各自的 {@code io.annona.modules.*}。
 */
package io.annona.shared.domain;
