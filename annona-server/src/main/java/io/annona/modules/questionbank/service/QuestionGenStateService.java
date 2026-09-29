package io.annona.modules.questionbank.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.questionbank.entity.QbGenerationTaskEntity;
import io.annona.modules.questionbank.model.QuestionGenConfig;
import io.annona.modules.questionbank.repository.QbGenerationTaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出题任务状态机（QUEUED→PROCESSING→COMPLETED/FAILED，重试回 QUEUED）。
 *
 * <p>全部转移走 repository 条件 UPDATE（fencing：taskId + 期望状态），本服务只做编排：
 * 创建带在途预检查（uq_generation_task_inflight 兜底并发窗口，撞约束由 DIVE 走 1006，
 * 与 direction create 同款处理），各转移方法返回 boolean 供调用方决定 ACK/RETRY。
 * 事务边界：本类方法各自最小事务；LLM 调用绝不在这些事务内（消费侧负责）。
 */
@Service
public class QuestionGenStateService {

    private final QbGenerationTaskRepository repository;

    public QuestionGenStateService(QbGenerationTaskRepository repository) {
        this.repository = repository;
    }

    /** 创建 QUEUED 任务；同 (user, direction) 已有在途任务即 2600（前端禁用提交按钮的正常路径）。 */
    @Transactional
    public QbGenerationTaskEntity createTask(UUID userId, UUID directionId,
                                             QuestionGenConfig config) {
        if (repository.existsByUserIdAndDirectionIdAndStatusIn(userId, directionId,
            List.of(QbGenerationTaskEntity.STATUS_QUEUED, QbGenerationTaskEntity.STATUS_PROCESSING))) {
            throw new BusinessException(ErrorCode.QB_GENERATION_TASK_IN_FLIGHT);
        }
        QbGenerationTaskEntity task = new QbGenerationTaskEntity();
        task.setId(UUID.randomUUID());
        task.setUserId(userId);
        task.setDirectionId(directionId);
        task.setStatus(QbGenerationTaskEntity.STATUS_QUEUED);
        task.setConfig(config);
        task.setUpdatedAt(Instant.now());
        return repository.save(task);
    }

    /** 原子领取；false = 任务已被其他实例领取或状态已变，消费侧安静放弃本条消息。 */
    @Transactional
    public boolean tryMarkProcessing(UUID taskId) {
        return repository.tryMarkProcessing(taskId, Instant.now()) > 0;
    }

    /** 完成落账；false = 状态已不匹配（理论上不该发生），调用方按异常路径记日志。 */
    @Transactional
    public boolean markCompleted(UUID taskId, int savedCount, int skippedCount, String message) {
        return repository.markCompleted(taskId, savedCount, skippedCount, message, Instant.now()) > 0;
    }

    /** 失败（重试耗尽或投递失败）；COMPLETED 不可被覆盖由条件保证。 */
    @Transactional
    public boolean markFailed(UUID taskId, String error) {
        return repository.markFailed(taskId, error, Instant.now()) > 0;
    }

    /** 消费失败未耗尽重试：PROCESSING→QUEUED，等恢复调度或重投递再领。 */
    @Transactional
    public boolean resetForRetry(UUID taskId) {
        return repository.resetForRetry(taskId, Instant.now()) > 0;
    }
}
