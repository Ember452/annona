package io.annona.modules.usage.service;

import io.annona.modules.usage.repository.TokenUsageRepository;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 用量记账器（llmprovider-metering-adr §记账）：账行写入永远不阻塞、不传染业务线程
 * ——事务内登记 afterCommit 回调（AGENTS 铁律：LLM 记账不进业务事务提交路径），
 * 无事务直接投 aiIoExecutor；落库失败只 warn。
 *
 * <p>取舍：接受"进程崩溃丢最近若干账行"换零阻塞——本账用于成本展示与配额，不是计费
 * 对账单；真计费（托管代持）需要 WAL/队列级可靠投递，那是那条 ADR 的前置议题。
 */
@Service
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    /** 一次调用的完整账目（字段语义 = token_usage 列，V11 注释为准）。 */
    public record UsageEntry(UUID userId, String scene, UUID sessionId, String provider,
                             String model, String purpose, int promptTokens, int completionTokens,
                             String promptHash, String evaluatorVersion) {
    }

    private final TokenUsageRepository repository;
    private final Executor aiIoExecutor;

    public UsageRecorder(TokenUsageRepository repository,
                         @Qualifier("aiIoExecutor") Executor aiIoExecutor) {
        this.repository = repository;
        this.aiIoExecutor = aiIoExecutor;
    }

    /** 记账入口（异步语义见类注释）；{@code userId==null}（无上下文调用）安静跳过。 */
    public void record(UsageEntry entry) {
        if (entry.userId() == null) {
            log.warn("用量记录缺归属用户，跳过：scene={} model={}", entry.scene(), entry.model());
            return;
        }
        Runnable write = () -> {
            try {
                repository.insertUsage(UUID.randomUUID(), entry.userId(), entry.scene(),
                    entry.sessionId(), entry.provider(), entry.model(), entry.purpose(),
                    entry.promptTokens(), entry.completionTokens(), entry.promptHash(),
                    entry.evaluatorVersion());
            } catch (RuntimeException e) {
                log.warn("用量落库失败（丢一帧账，不阻塞业务）：{}", e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    aiIoExecutor.execute(write);
                }
            });
        } else {
            aiIoExecutor.execute(write);
        }
    }
}
