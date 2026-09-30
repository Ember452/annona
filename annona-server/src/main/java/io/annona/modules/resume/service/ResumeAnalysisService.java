package io.annona.modules.resume.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.parse.DocumentBlock;
import io.annona.common.parse.DocumentParser;
import io.annona.common.storage.ObjectStorage;
import io.annona.common.stream.TaskStreamPort;
import io.annona.common.usage.UsageContext;
import io.annona.modules.resume.entity.ResumeEntity;
import io.annona.modules.resume.model.ResumeAnalysis;
import io.annona.modules.resume.repository.ResumeRepository;
import io.annona.shared.ai.StructuredOutputInvoker;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 简历分析消费业务（P1b-08，{@link TaskStreamPort.TaskMessageHandler}）。领取（条件 UPDATE）后：
 * 从对象存储取原文 → Tika 解析正文 → {@link StructuredOutputInvoker} 结构化分析（同步 chat，经
 * @Primary MeteredModelProvider 自动记 RESUME 账）→ 落 {@code analysis} 并置 DONE。
 *
 * <p>铁律：存储读、解析、LLM 全在事务外；DB 写（领取/落账/失败）各走 {@link TransactionTemplate}
 * 短事务（消费线程无事务，@Modifying 缺事务必抛）。执行权失效（他人已领/已 DONE）一律 ACK 丢弃。
 * 投递丢失或消费崩溃由 {@code ResumeRecoveryScheduler} 双阈值兜底。
 */
@Service
public class ResumeAnalysisService implements TaskStreamPort.TaskMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ResumeAnalysisService.class);

    static final int MAX_RETRY = 3;
    /** 送入 prompt 的正文上限（超长截断，防 prompt 爆；简历正文通常远小于此）。 */
    private static final int MAX_TEXT_CHARS = 12000;

    private final ResumeRepository resumeRepository;
    private final ObjectProvider<ObjectStorage> objectStorage;
    private final DocumentParser documentParser;
    private final ObjectProvider<StructuredOutputInvoker> invoker;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String systemPrompt;
    private final String userPromptTemplate;

    public ResumeAnalysisService(ResumeRepository resumeRepository,
                                 ObjectProvider<ObjectStorage> objectStorage,
                                 DocumentParser documentParser,
                                 ObjectProvider<StructuredOutputInvoker> invoker,
                                 PlatformTransactionManager transactionManager) {
        this.resumeRepository = resumeRepository;
        this.objectStorage = objectStorage;
        this.documentParser = documentParser;
        this.invoker = invoker;
        this.tx = new TransactionTemplate(transactionManager);
        this.systemPrompt = readPrompt("prompts/resume-analysis-system.st");
        this.userPromptTemplate = readPrompt("prompts/resume-analysis-user.st");
    }

    @Override
    public TaskStreamPort.Outcome handle(String msgId, Map<String, String> payload, int retryCount) {
        UUID resumeId;
        try {
            resumeId = UUID.fromString(payload.get("resumeId"));
        } catch (IllegalArgumentException e) {
            return TaskStreamPort.Outcome.ACK;
        }
        ResumeEntity resume = resumeRepository.findById(resumeId).orElse(null);
        if (resume == null || ResumeEntity.STATUS_DONE.equals(resume.getStatus())) {
            return TaskStreamPort.Outcome.ACK; // 已删或已终态
        }
        Integer claimed = tx.execute(s ->
            resumeRepository.tryMarkProcessing(resumeId, Instant.now()));
        if (claimed == null || claimed == 0) {
            return TaskStreamPort.Outcome.ACK; // 他人已接手
        }
        try {
            analyze(resume);
            return TaskStreamPort.Outcome.ACK;
        } catch (Exception e) {
            log.warn("简历分析 {} 失败（retryCount={}）：{}", resumeId, retryCount, e.getMessage());
            if (retryCount < MAX_RETRY && resetToPending(resumeId)) {
                return TaskStreamPort.Outcome.RETRY;
            }
            tx.executeWithoutResult(s -> resumeRepository.markFailed(resumeId, safeMessage(e),
                Instant.now()));
            return TaskStreamPort.Outcome.DEAD;
        }
    }

    private void analyze(ResumeEntity resume) {
        ObjectStorage storage = objectStorage.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(ErrorCode.RESUME_STORAGE_NOT_CONFIGURED);
        }
        byte[] content = storage.get(resume.getStorageKey());
        String text = extractText(content, resume.getOriginalFilename());
        if (text.isBlank()) {
            throw new BusinessException(ErrorCode.RESUME_TYPE_NOT_SUPPORTED, "无法从简历解析出文本");
        }
        StructuredOutputInvoker bean = invoker.getIfAvailable();
        if (bean == null) {
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "模型未配置，无法分析简历");
        }
        ResumeAnalysis analysis;
        try (UsageContext.Scope ignored = UsageContext.bind(
            resume.getUserId().toString(), "RESUME", resume.getId(), null)) {
            analysis = bean.invoke(systemPrompt,
                userPromptTemplate.replace("{resumeText}", text), ResumeAnalysis.class);
        }
        String analysisJson = toJson(analysis);
        tx.executeWithoutResult(s -> resumeRepository.markDone(resume.getId(), analysisJson,
            Instant.now()));
    }

    private String extractText(byte[] content, String filename) {
        String joined = documentParser.parse(content, filename).stream()
            .map(DocumentBlock::text)
            .collect(Collectors.joining("\n"));
        return joined.length() > MAX_TEXT_CHARS ? joined.substring(0, MAX_TEXT_CHARS) : joined;
    }

    private boolean resetToPending(UUID resumeId) {
        Integer reset = tx.execute(s ->
            resumeRepository.markProcessingBackToPending(resumeId, Instant.now()));
        return reset != null && reset == 1;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "分析结果序列化失败");
        }
    }

    private static String safeMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? "简历分析失败" : (m.length() > 200 ? m.substring(0, 200) : m);
    }

    private static String readPrompt(String resource) {
        try (InputStream in = ResumeAnalysisService.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少简历分析 prompt 资源：" + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
