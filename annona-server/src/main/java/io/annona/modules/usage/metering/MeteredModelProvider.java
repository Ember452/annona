package io.annona.modules.usage.metering;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.quota.DailyQuotaCounter;
import io.annona.common.usage.UsageContext;
import io.annona.modules.usage.config.UsageProperties;
import io.annona.modules.usage.service.UsageRecorder;
import io.annona.spi.dto.ModelChatMessage;
import io.annona.spi.dto.ModelOptions;
import io.annona.spi.dto.ModelResponse;
import io.annona.spi.dto.UsageInfo;
import io.annona.spi.model.ModelProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 模型网关计量装饰器（P1b-10 核心）：业务代码统一注入本 {@code @Primary} bean，
 * chat 出口在此完成<b>前置熔断 → 调用 → 记账</b>三步——出题（经 StructuredOutputInvoker）
 * 与后续评估器天然全覆盖，调用点零改动。
 *
 * <p>三个刻意的顺序决定：
 * ① 熔断在调用<b>前</b>（读当日累计判限）：超了还打出去，钱已经花了，拒绝毫无意义；
 * ② 委托解析惰性且排除自身（ObjectProvider.stream）：被包装的原始 provider 缺席
 *    （模型未配置）时上下文照常装配（门控禁令），调用才报 1100；
 * ③ promptHash 在此算而不是调用方：拦截点看得见 messages 原文，SHA-256 只存哈希
 *    （评估可比性留痕，正文零残留——敏感数据规范）。
 */
@Component
@Primary
public class MeteredModelProvider implements ModelProvider {

    private static final String QUOTA_KEY_PREFIX = "quota:daily:";

    private final ObjectProvider<ModelProvider> candidates;
    private final UsageRecorder recorder;
    private final DailyQuotaCounter quota;
    private final UsageProperties properties;

    public MeteredModelProvider(ObjectProvider<ModelProvider> candidates, UsageRecorder recorder,
                                DailyQuotaCounter quota, UsageProperties properties) {
        this.candidates = candidates;
        this.recorder = recorder;
        this.quota = quota;
        this.properties = properties;
    }

    @Override
    public String name() {
        return delegate().name();
    }

    @Override
    public String channel() {
        return delegate().channel();
    }

    @Override
    public ModelResponse chat(List<ModelChatMessage> messages, ModelOptions options) {
        UsageContext.Attribution attribution = UsageContext.current().orElse(null);
        if (attribution == null) {
            // 归属不明的调用不记账不计数（宁缺毋滥：错账比缺账更难排查）；
            // 熔断也跳过——没有"谁超限"可判
            return delegate().chat(messages, options);
        }
        String userId = attribution.userId();
        if (properties.getDailyTokenLimit() > 0
            && quota.current(dayKey(userId)) >= properties.getDailyTokenLimit()) {
            throw new BusinessException(ErrorCode.QUOTA_EXCEEDED);
        }
        ModelResponse response = delegate().chat(messages, options);
        UsageInfo usage = response.usage();
        int prompt = usage == null ? 0 : usage.promptTokens();
        int completion = usage == null ? 0 : usage.completionTokens();
        long total = (long) prompt + completion;
        if (total > 0) {
            quota.tryConsume(dayKey(userId), total, properties.getDailyTokenLimit(),
                Duration.between(LocalDateTime.now(),
                    LocalDate.now().plusDays(1).atStartOfDay()));
        }
        recorder.record(new UsageRecorder.UsageEntry(
            UUID.fromString(userId), attribution.scene(), attribution.sessionId(),
            // provider = 供应通道（channel），model = 响应模型 id：两列语义独立（TD-03，
            // 旧写法两处同值让通道归因失真）；响应不报 model 时退 name()，再退 unknown
            delegate().channel(),
            response.model() == null ? name() : response.model(),
            "chat", prompt, completion, hashOf(messages), attribution.evaluatorVersion()));
        return response;
    }

    /** 排除自身的第一个原始 provider；全无 → 1100（与 StructuredOutputInvoker 同文案纪律）。 */
    private ModelProvider delegate() {
        return candidates.stream()
            .filter(provider -> provider != this)
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                "模型未配置，无法调用"));
    }

    private static String dayKey(String userId) {
        return QUOTA_KEY_PREFIX + userId + ":" + LocalDate.now();
    }

    private static String hashOf(List<ModelChatMessage> messages) {
        StringJoiner joiner = new StringJoiner("\n");
        for (ModelChatMessage message : messages) {
            joiner.add(message.role() + ":" + message.content());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(joiner.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
