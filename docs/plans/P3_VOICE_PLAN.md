# P3 语音实施计划（VOICE_PLAN）

- 日期 / 状态：2026-10-06 / Active（批 1 执行中）
- 承接：[开发计划 P3 任务表](../annona-开发计划.md)（P3-01..07，12 人日）、[voice-adr](../specs/2026-10-06-voice-adr.md)
- 依据：P2 关账后用户裁决——分三批推进；DashScope 为 ASR/TTS 首个真实现；**独立语音会话**（不套用 interview_session）。

## 1. 批次划分

### 批 1：端口 + WS 链路 + 流式 ASR（本批，≈P3-01 全部 + P3-02 前半）

| 交付物 | 说明 |
|---|---|
| common 端口 | `StreamingAsrProvider`（start/sendAudio/close + onReady/onPartial/onFinal/onError）、`TtsProvider`（句级一次性 `synthesize`）——[voice-adr §决策 1](../specs/2026-10-06-voice-adr.md) |
| infrastructure 实现 | DashScope qwen3-asr/tts-flash-realtime 直连（JDK WebSocket，无 SDK）+ Fake 双实现 + `annona.model.asr/tts.*` 三态装配（none/fake/dashscope） |
| voice 模块骨架 | V18 `voice_session` + `/ws/voice` 握手鉴权 + JSON 协议 + 会话生命周期（暂停/恢复/超时）+ 回声半双工 + 手动提交 + Micrometer 埋点 |
| 前端地基 | AudioWorklet pcm-processor（16k/200ms）+ useVoiceSession（重连/事件归一 reducer + vitest）+ AudioRecorder/RealtimeSubtitle/voice 页骨架 |
| 批出口 | fake 全链路 docker-it 绿 + mvnw verify 绿 + 前端四门绿 + DashScope 实现编译通过 + 最小真 Key 冒烟教程 |

### 批 2：句级并发 TTS + 评估接入（P3-03/04/05 剩余）

- `OrderedTtsChunkEmitter`：LLM 流式按句切分、信号量限并发、按序推 `audio_chunk`（首句优先、边合成边播）。
- 多轮上下文 + `VoiceContextCompressor`/`VoiceHistoryLoader`（🅖 同构）；开场白配置化（yaml + 预热开关）。
- **评估接入（P3-05 可比性）**：voice 交卷发独立领域事件；评估侧经语音会话共享读接口装配内容，评分口径仍走 `QuestionQueryService.gradingByIds`；报告归属方案（interview_report 扩 session 类型 vs 语音报告表）在装配落地时以 ADR 修订定案。
- LLM 通道接 `StreamingChatProvider`（qa 同款），追问文本逐句喂 TTS。

### 批 3：延迟实测 + 压力面 + 收尾（P3-06/07）

- 端到端延迟预算表（停止说话→首包音频）+ P50/P95 实测 + `docs/tests/指标测试-语音延迟.md`；**不达标按设计降级为"一键朗读答案 + 文字作答"，不许拿 TTS 首包数字充数**。
- 多面试官压力面（单会话 3 角色轮流追问，prompt 级角色轮换）。
- 阶段总结 + 开发计划/README 回写。

## 2. 出口条件映射（开发计划 P3 §出口）

| 出口 | 由哪批闭合 |
|---|---|
| ① 端到端延迟有实测分布 | 批 3（P3-06） |
| ② 语音结果进同一评估引擎且可比 | 批 2（P3-05） |
| ③ 降级路径已实现或被验证不需要 | 批 2 实现（TTS→文字、ASR→手动提交，ADR §决策 7） |
| ④ 阶段总结已写 | 批 3 |

## 3. 风险与前置

- **DashScope 直连协议**：🅖 走 dashscope-sdk-java，annona 无 SDK 直连按 Omni realtime 协议拼帧（session.update / input_audio_buffer.append / commit / session.finish）；事件形状以 🅖 回调处理的 server 事件名为准。首包不通时最小复现步骤在手工验收教程里，必要时批内改引 SDK 只进 infrastructure（端口契约不变，不违零 SDK 纪律——spi/common 不动）。
- **无耳机回声**：浏览器 echoCancellation + 服务端半双工双保险；纯扬声器外放场景验收手动提交模式。
- **配额语义**：ASR 秒数 / TTS 字符数借 `token_usage` tokens 列（voice-adr §决策 6），托管收紧前 fail-open 现状不变。
