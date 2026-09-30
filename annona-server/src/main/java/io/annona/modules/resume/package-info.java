/**
 * resume 简历上传与异步 AI 分析（P1b-08）。
 *
 * <p>职责：简历文件上传（对象存储 + hash 幂等）→ Tika 解析正文 → 异步 LLM 结构化分析
 * （摘要/亮点/关注点/技能）落 {@code resume.analysis}，状态机 PENDING→PROCESSING→DONE/FAILED
 * （Redis Stream + 恢复调度，复用 questionbank 形状）；分析结果可作后续面试的上下文。
 *
 * <p>允许依赖：{@code io.annona.common}（TaskStreamPort/UsageContext/ObjectStorage/DocumentParser/
 * ContentHashes/异常）、{@code io.annona.spi}（ModelProvider 经 shared StructuredOutputInvoker）、
 * {@code io.annona.shared.ai}。不 import 其它业务模块（读走 shared、写走事件、复用件走 common）。
 * LLM/解析/S3 一律事务外，DB 写最小短事务。与 interview 的"简历→面试上下文"接线尚未建（见收口小结）。
 */
package io.annona.modules.resume;
