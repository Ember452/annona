package io.annona.modules.plan.listener;

import io.annona.modules.plan.entity.PlanTaskEntity;
import io.annona.modules.plan.repository.PlanTaskRepository;
import io.annona.shared.domain.CheckinLinkedEvent;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 打卡联动监听器（plan-module-adr §决策 3）：打卡首建发布的事件 → 把分钟数瀑布式累计进
 * 该用户该方向的 PENDING 任务（最旧优先），进度达 target 自动 DONE。
 *
 * <p>装配语义与 EvaluationTrigger 同款：AFTER_COMMIT（打卡主事务不因联动失败回滚）+
 * REQUIRES_NEW（独立事务写进度）+ 门控（annona.plan.enabled=false 时 bean 消失，
 * 事件无订阅者自然丢弃，打卡主链路零感知）。LLM/Redis IO 不在此处（纯本地库写）。
 */
@Component
@ConditionalOnProperty(value = "annona.plan.enabled", havingValue = "true", matchIfMissing = true)
public class CheckinProgressListener {

    private final PlanTaskRepository taskRepository;

    @Autowired
    public CheckinProgressListener(PlanTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(CheckinLinkedEvent event) {
        if (event.directionId() == null || event.minutes() <= 0) {
            return;
        }
        List<PlanTaskEntity> tasks = taskRepository
            .findByUserIdAndDirectionIdAndStatusOrderByCreatedAtAsc(
                event.userId(), event.directionId(), PlanTaskEntity.STATUS_PENDING);
        int remaining = event.minutes();
        for (PlanTaskEntity task : tasks) {
            if (remaining <= 0) {
                break;
            }
            // 瀑布：本任务吃满剩余缺口，吃不完的留给下一任务；已满即自动完成
            int add = Math.min(remaining, task.getTargetMinutes() - task.getProgressMinutes());
            if (add > 0) {
                task.setProgressMinutes(task.getProgressMinutes() + add);
                remaining -= add;
            }
            if (task.getProgressMinutes() >= task.getTargetMinutes()) {
                task.setStatus(PlanTaskEntity.STATUS_DONE);
            }
            taskRepository.save(task);
        }
    }
}
