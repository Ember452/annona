package io.annona.spi.fake;

import io.annona.spi.dto.ModelChatMessage;
import io.annona.spi.dto.ModelOptions;
import io.annona.spi.dto.ModelResponse;
import io.annona.spi.dto.UsageInfo;
import io.annona.spi.model.ModelProvider;
import java.util.List;

/**
 * {@link ModelProvider} 的内存实现。返回一段固定文本，token 使用量按字符数粗略估算，
 * 让 usage 计量代码在测试里能验到"非零"而不是全部零值。
 *
 * <p>不真发 HTTP，任何 profile 下都可以装配；测试里若需要特定响应，用
 * {@link #FakeModelProvider(String)} 传固定内容进来即可。
 */
public final class FakeModelProvider implements ModelProvider {

    /** 与 {@code annona.model.default} 的取值 {@code fake} 对齐。 */
    public static final String NAME = "fake";

    private static final String DEFAULT_REPLY = "fake-model-response";
    private static final String MODEL_TAG = "fake-chat-v0";

    private final String reply;

    public FakeModelProvider() {
        this(DEFAULT_REPLY);
    }

    public FakeModelProvider(String reply) {
        this.reply = reply;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ModelResponse chat(List<ModelChatMessage> messages, ModelOptions options) {
        int promptTokens = estimateTokens(messages);
        int completionTokens = reply.length() / 4;
        String usedModel = (options != null && options.model() != null) ? options.model() : MODEL_TAG;
        return new ModelResponse(reply, new UsageInfo(promptTokens, completionTokens), usedModel);
    }

    private static int estimateTokens(List<ModelChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int chars = messages.stream()
            .mapToInt(m -> m.content() == null ? 0 : m.content().length())
            .sum();
        return chars / 4;
    }
}
