package io.annona.modules.knowledge.listener;

import io.annona.common.stream.TaskStreamPort;
import io.annona.modules.knowledge.embed.KnowledgeVectorizeService;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 向量化任务流：key / 消费组命名与消息形状（瘦消息：docId + retryCount，借 🅖）
 * 集中在此；生产侧（上传/恢复调度）经 {@link #send}，消费侧经 {@link SmartLifecycle}
 * 在<b>全部单例装配完成后</b>启动——不能在构造器里直接 consume：后台线程会在
 * context refresh 期间抢夺单例锁（其他 bean 还没建完），既有死锁风险也有日志噪音
 * （P1a-05 docker-it 实测）。线程随应用停机由端口的 @PreDestroy 中断。
 *
 * <p>enabled 门控（默认开，与恢复调度的 recovery.enabled 并列）：SmartLifecycle bean
 * 与 @Scheduled 一样会被 LifecycleProcessor 在 refresh 期强制实例化、绕过 test profile
 * 的全局懒加载——无 JPA/Redis 的冒烟上下文必须能关掉整条后台机器。
 */
@Component
@ConditionalOnProperty(prefix = "annona.knowledge", name = "ingest.enabled",
    havingValue = "true", matchIfMissing = true)
public class KnowledgeVectorizeStream implements SmartLifecycle {

    public static final String STREAM_KEY = "knowledge:vectorize:stream";
    /** 消费组名（借 🅖 vectorize-group）。 */
    public static final String GROUP = "vectorize-group";

    private final TaskStreamPort taskStreamPort;
    private final KnowledgeVectorizeService handler;
    private boolean running;

    public KnowledgeVectorizeStream(TaskStreamPort taskStreamPort, KnowledgeVectorizeService handler) {
        this.taskStreamPort = taskStreamPort;
        this.handler = handler;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        // 消费者名拼实例随机后缀，多实例同机部署时不串消费分区
        String consumerName = "vectorize-consumer-" + UUID.randomUUID().toString().substring(0, 8);
        taskStreamPort.consume(TaskStreamPort.ConsumerSpec.of(STREAM_KEY, GROUP, consumerName), handler);
        running = true;
    }

    @Override
    public void stop() {
        running = false; // 消费线程随 JVM 退出（daemon）与端口侧 @PreDestroy 中断收尾
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * @return 是否投递成功；false = Redis 不可用（调用方把文档判 FAILED，恢复调度兜底）
     */
    public boolean send(UUID docId) {
        return taskStreamPort.send(STREAM_KEY, Map.of("docId", docId.toString()));
    }
}
