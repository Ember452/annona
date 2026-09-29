package io.annona.shared.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.spi.dto.ModelChatMessage;
import io.annona.spi.dto.ModelOptions;
import io.annona.spi.model.ModelProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 结构化输出统一重试器（借 🅖 common/ai/StructuredOutputInvoker 的重试语义）：
 * 调 chat → 从回复中提取 JSON → 解析失败带"上次错误"反馈重试 → 耗尽抛业务异常。
 * 出题管道（P1b-02）与评估（P1b-06）共用，避免两处各写一套重试。
 *
 * <p>放 {@code shared/ai} 而非计划中的 common：依赖方向 modules→spi→common，
 * common 看不见 spi 的 {@link ModelProvider}（skill-questionbank-adr §后续修订）。
 * ModelProvider 经 {@link ObjectProvider} 注入（条件装配顺序陷阱，本仓惯例）：
 * 模型未配置时调用即抛 AI_SERVICE_UNAVAILABLE，由消费方翻译成安全文案。
 */
@Component
@EnableConfigurationProperties(StructuredOutputProperties.class)
public class StructuredOutputInvoker {

    private static final String RETRY_HINT = "\n\n上一次输出无法解析为约定 JSON，原因：";
    private static final String STRICT_REMINDER =
        "。请严格只输出符合约定结构的 JSON 正文：不要 markdown 代码块、不要解释文字、"
            + "不要在字符串值里出现未转义的引号或换行。";

    private final ObjectProvider<ModelProvider> provider;
    private final StructuredOutputProperties properties;
    private final ObjectMapper objectMapper;

    public StructuredOutputInvoker(ObjectProvider<ModelProvider> provider,
                                   StructuredOutputProperties properties,
                                   ObjectMapper objectMapper) {
        this.provider = provider;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步调用并把回复解析为 {@code type}。失败重试时在 user 消息后追加解析错误反馈
     * （上游口径：错误信息回填是结构化重试的关键，比单纯"再试一次"成功率高）。
     *
     * @throws BusinessException 重试耗尽仍无法解析（AI_SERVICE_ERROR）；调用方翻译成自己的安全文案
     */
    public <T> T invoke(String systemPrompt, String userPrompt, Class<T> type) {
        List<ModelChatMessage> messages = new ArrayList<>();
        messages.add(new ModelChatMessage("system", systemPrompt));
        messages.add(new ModelChatMessage("user", userPrompt));

        String lastError = "";
        ModelProvider chat = provider.getIfAvailable();
        if (chat == null) {
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                "模型未配置，无法执行结构化生成");
        }
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            String content = chat.chat(messages, ModelOptions.defaults()).content();
            try {
                return objectMapper.readValue(extractJson(content), type);
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                messages = new ArrayList<>(messages);
                messages.set(messages.size() - 1, new ModelChatMessage("user",
                    userPrompt + RETRY_HINT + lastError + STRICT_REMINDER));
            }
        }
        throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
            "结构化输出解析失败（重试 " + properties.getMaxAttempts() + " 次）：" + lastError);
    }

    /**
     * 剥掉模型惯用的 markdown 代码围栏与前后闲话：取首个 '{' 到末个 '}' 的片段。
     * 找不到结构体时原文返回，让解析步骤报出可读错误（而不是这里抛出含混的越界）。
     */
    static String extractJson(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.strip();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return trimmed;
        }
        return trimmed.substring(start, end + 1);
    }
}
