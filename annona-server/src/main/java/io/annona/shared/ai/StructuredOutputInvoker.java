package io.annona.shared.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.spi.dto.ModelChatMessage;
import io.annona.spi.dto.ModelOptions;
import io.annona.spi.model.ModelProvider;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * ObjectMapper 不注入而是自建——docker-it / compose 的 NONE 上下文没有 Boot 的
 * ObjectMapper bean（SseProgressHub 同款先例），而解析 LLM 输出不依赖 spring.jackson.* 定制。
 */
@Component
@EnableConfigurationProperties(StructuredOutputProperties.class)
public class StructuredOutputInvoker {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputInvoker.class);

    private static final String RETRY_HINT = "\n\n上一次输出无法解析为约定 JSON，原因：";
    private static final String STRICT_REMINDER =
        "。请严格只输出符合约定结构的 JSON 正文：不要 markdown 代码块、不要解释文字、"
            + "不要在字符串值里出现未转义的引号或换行。";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ObjectProvider<ModelProvider> provider;
    private final StructuredOutputProperties properties;

    public StructuredOutputInvoker(ObjectProvider<ModelProvider> provider,
                                   StructuredOutputProperties properties) {
        this.provider = provider;
        this.properties = properties;
    }

    /**
     * 同步调用并把回复解析为 {@code type}。失败重试时在 user 消息后追加解析错误反馈
     * （上游口径：错误信息回填是结构化重试的关键，比单纯"再试一次"成功率高）。
     *
     * <p>对外只暴露安全文案：原始解析错误（Jackson 详情、模型返回正文）只进日志、
     * 不进异常 message——本方法被 {@code QuestionGenerationService} 等消费方经
     * {@code safeMessage} 原样写入 {@code task.error}，会透出到前端
     * （skill-questionbank-adr §后果与约束"不透传模型原始报错"）。
     *
     * @throws BusinessException 重试耗尽仍无法解析（AI_SERVICE_ERROR，安全文案）；调用方可直接用文案或再翻译成更具体的业务描述
     */
    public <T> T invoke(String systemPrompt, String userPrompt, Class<T> type) {
        return invokeWithRaw(systemPrompt, userPrompt, type).parsed();
    }

    /**
     * 同 {@link #invoke} 的重试语义，但额外返回<b>最后一次模型的原始正文</b>——解析成功时为
     * 该次返回体，重试耗尽时为最后一次尝试的正文。评估链靠它在降级时保留逐题原文
     * （出口③，evaluation-pipeline-adr），避免在业务代码里复制一套重试。
     */
    public <T> StructuredResult<T> invokeWithRaw(String systemPrompt, String userPrompt, Class<T> type) {
        List<ModelChatMessage> messages = new ArrayList<>();
        messages.add(new ModelChatMessage("system", systemPrompt));
        messages.add(new ModelChatMessage("user", userPrompt));

        String lastError = "";
        String lastRaw = "";
        ModelProvider chat = provider.getIfAvailable();
        if (chat == null) {
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                "模型未配置，无法执行结构化生成");
        }
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            String content = chat.chat(messages, ModelOptions.defaults()).content();
            lastRaw = content;
            try {
                return new StructuredResult<>(OBJECT_MAPPER.readValue(extractJson(content), type), content);
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                // 原始解析错误与模型正文只进日志（供诊断），不外泄；lastError 回填给模型重试是内部通路
                log.warn("结构化输出第 {} 次解析失败：{}｜模型返回：{}", attempt, lastError, content);
                messages = new ArrayList<>(messages);
                messages.set(messages.size() - 1, new ModelChatMessage("user",
                    userPrompt + RETRY_HINT + lastError + STRICT_REMINDER));
            }
        }
        // 耗尽：仍把最后一次的原始正文交给调用方（评估链据此落 raw_response，出口③）
        throw new StructuredOutputUnparsedException(
            "结构化输出解析失败（重试 " + properties.getMaxAttempts() + " 次）", lastRaw);
    }

    /** 解析成功结果 + 模型原始正文。 */
    public record StructuredResult<T>(T parsed, String raw) {
    }

    /**
     * 重试耗尽仍无法解析——携带最后一次模型原文（{@code lastRaw}），供评估链保留逐题原文。
     * message 仍是安全文案（不透传 Jackson 详情/模型正文），正文经 {@code lastRaw} 单独取。
     */
    public static final class StructuredOutputUnparsedException extends BusinessException {
        private final String lastRaw;

        public StructuredOutputUnparsedException(String safeMessage, String lastRaw) {
            super(ErrorCode.AI_SERVICE_ERROR, safeMessage);
            this.lastRaw = lastRaw;
        }

        public String lastRaw() {
            return lastRaw;
        }
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
