package io.annona.infrastructure.llm;

import io.annona.common.model.ChatMessage;
import io.annona.common.model.ChatStreamListener;
import io.annona.common.model.StreamingChatProvider;
import io.annona.spi.dto.UsageInfo;
import java.util.List;

/**
 * 确定性流式 fake（落 infrastructure 而非 spi/fake 的理由见 qa-streaming-adr §决策 5：
 * 流式端口在 common，spi 装不下）。供无 Key 的 slice 与 CI 跑通问答链路；回答与输入
 * 无语义关系，数字/文案不可读作质量。分三段 delta 模拟逐字输出，且载荷含换行——
 * 前端与 QaFlowIT 借它验证多行正文经 JSON 信封的往返。
 */
public class FakeStreamingChatProvider implements StreamingChatProvider {

    /** 完整回答（与 {@link #DELTAS} 的拼接严格一致，QaFlowIT 与前端测试按此断言）。 */
    public static final String RESPONSE =
        "这是 fake chat 的确定性回答：\n\n- 第一点\n- 第二点\n\n与你的提问无语义关系。";

    private static final List<String> DELTAS = List.of(
        "这是 fake chat 的确定性回", "答：\n\n- 第一", "点\n- 第二点\n\n与你的提问无语义关系。");

    @Override
    public String name() {
        return "fake-chat";
    }

    @Override
    public String channel() {
        return "fake";
    }

    @Override
    public long streamTimeoutMillis() {
        // 与 application.yaml 的 chat timeout-seconds 默认值同量级（fake 无真上游，只供派生链有值）
        return 60_000L;
    }

    @Override
    public void streamChat(List<ChatMessage> messages, ChatStreamListener listener) {
        for (String delta : DELTAS) {
            listener.onDelta(delta);
        }
        // 确定性假 usage（与 FakeModelProvider 同口径 chars/4）：让 qa 计量链在 slice/CI
        // 能验"非零"；数字不可读作成本事实（fake 无真上游）
        int promptChars = messages.stream().mapToInt(m -> m.content() == null ? 0 : m.content().length()).sum();
        listener.onComplete(RESPONSE, new UsageInfo(promptChars / 4, RESPONSE.length() / 4));
    }
}
