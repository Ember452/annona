package io.annona.modules.questionbank.listener;

import io.annona.common.stream.TaskStreamPort;
import io.annona.modules.questionbank.service.QuestionGenerationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 出题任务流（P1b-02）：key/消费组/瘦消息形状集中在此（{taskId, retryCount}，借 🅖）。
 * SmartLifecycle 启动时机与门控理由同 KnowledgeVectorizeStream——不能在构造器里 consume
 * （refresh 期抢单例锁死锁），SmartLifecycle 会被 test profile 之外的上下文强制实例化，
 * 无 Redis 的冒烟上下文用 generate.enabled 关掉整条机器。
 */
@Component
@ConditionalOnProperty(prefix = "annona.questionbank", name = "generate.enabled",
    havingValue = "true", matchIfMissing = true)
public class QuestionGenStream implements SmartLifecycle {

    public static final String STREAM_KEY = "questionbank:question-gen:stream";
    public static final String GROUP = "question-gen-group";

    private final TaskStreamPort taskStreamPort;
    private final QuestionGenerationService handler;
    private boolean running;

    public QuestionGenStream(TaskStreamPort taskStreamPort, QuestionGenerationService handler) {
        this.taskStreamPort = taskStreamPort;
        this.handler = handler;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        String consumerName = "qb-gen-consumer-" + UUID.randomUUID().toString().substring(0, 8);
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

    /** @return 是否投递成功；false = Redis 不可用（调用方把任务判 FAILED，恢复调度兜底）。 */
    public boolean send(UUID taskId) {
        return taskStreamPort.send(STREAM_KEY,
            Map.of("taskId", taskId.toString(), TaskStreamPort.RETRY_COUNT_FIELD, "0"));
    }
}
