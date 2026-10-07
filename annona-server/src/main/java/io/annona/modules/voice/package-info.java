/**
 * 语音面试模块（P3，voice-adr）：/ws/voice 双向音频 + 流式 ASR + TTS 播报的对话式面试。
 *
 * <p>允许依赖：common 的语音端口（StreamingAsrProvider/TtsProvider）、
 * infrastructure 运行期装配的 provider bean、shared 方向/题库只读读模型；
 * 与其他业务模块零直连（评估接入走领域事件，批 2 落地）。
 *
 * <p>隐私红线：语音原始音频不落库不落对象存储，唯一存留物是转写文本与延迟指标
 * （voice-adr §决策 3）。延迟埋点口径：端到端（停止说话→首包音频）是唯一对外
 * 验收数字，分段计时只用于链路归因（设计文档 §10）。
 */
package io.annona.modules.voice;
