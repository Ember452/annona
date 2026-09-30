package io.annona.modules.evaluation.listener;

import io.annona.common.stream.TaskStreamPort;
import io.annona.modules.evaluation.service.EvaluationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 评估任务流（P1b-06）：key/消费组/瘦消息形状集中在此（{sessionId, retryCount}，仿
 * {@code QuestionGenStream}）。SmartLifecycle 启动时机与门控理由同先例——不能在构造器里
 * consume（refresh 期抢单例锁死锁）；无 Redis 的冒烟上下文用 {@code annona.evaluation.enabled}
 * 关掉整条机器。门控 bean 由 {@code EvaluationTrigger}/{@code EvaluationRecoveryScheduler} 经
 * ObjectProvider 注入（禁止硬注入，AGENTS §4 门控禁令）。
 */
@Component
@ConditionalOnProperty(prefix = "annona.evaluation", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class EvaluationStream implements SmartLifecycle {

    public static final String STREAM_KEY = "evaluation:interview:stream";
    public static final String GROUP = "evaluation-group";

    private final TaskStreamPort taskStreamPort;
    private final EvaluationService handler;
    private boolean running;

    public EvaluationStream(TaskStreamPort taskStreamPort, EvaluationService handler) {
        this.taskStreamPort = taskStreamPort;
        this.handler = handler;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        String consumerName = "eval-consumer-" + UUID.randomUUID().toString().substring(0, 8);
        taskStreamPort.consume(TaskStreamPort.ConsumerSpec.of(STREAM_KEY, GROUP, consumerName),
            handler);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** @return 是否投递成功；false = Redis 不可用（报告留 PENDING，恢复调度兜底补投）。 */
    public boolean send(UUID sessionId) {
        return taskStreamPort.send(STREAM_KEY,
            Map.of("sessionId", sessionId.toString(), TaskStreamPort.RETRY_COUNT_FIELD, "0"));
    }
}
