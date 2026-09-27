package io.annona.modules.knowledge.listener;

import io.annona.common.stream.TaskStreamPort;
import io.annona.modules.knowledge.embed.KnowledgeVectorizeService;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 向量化任务流：key / 消费组命名与消息形状（瘦消息：docId + retryCount，借 🅖）
 * 集中在此；生产侧（上传/恢复调度）经 {@link #send}，消费侧在装配时注册
 * {@link KnowledgeVectorizeService}（端口线程随应用启停）。
 */
@Component
public class KnowledgeVectorizeStream {

    public static final String STREAM_KEY = "knowledge:vectorize:stream";
    /** 消费组名（借 🅖 vectorize-group）。 */
    public static final String GROUP = "vectorize-group";

    private final TaskStreamPort taskStreamPort;

    public KnowledgeVectorizeStream(TaskStreamPort taskStreamPort, KnowledgeVectorizeService handler) {
        this.taskStreamPort = taskStreamPort;
        this.taskStreamPort.consume(
            TaskStreamPort.ConsumerSpec.of(STREAM_KEY, GROUP, consumerName()), handler);
    }

    /** 消费者名拼实例随机后缀，多实例同机部署时不串消费分区。 */
    private static String consumerName() {
        return "vectorize-consumer-"
            + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * @return 是否投递成功；false = Redis 不可用（调用方把文档判 FAILED，恢复调度兜底）
     */
    public boolean send(UUID docId) {
        return taskStreamPort.send(STREAM_KEY, Map.of("docId", docId.toString()));
    }
}
