package io.annona.modules.resume.listener;

import io.annona.common.stream.TaskStreamPort;
import io.annona.modules.resume.service.ResumeAnalysisService;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 简历分析任务流（P1b-08）：key/消费组/瘦消息（{resumeId, retryCount}，仿 QuestionGenStream）。
 * SmartLifecycle 启动时机与门控理由同先例（不能在构造器 consume；无 Redis 冒烟上下文用
 * {@code annona.resume.enabled} 关掉）。生产路径（上传/恢复）直连 TaskStreamPort，与本消费门控
 * 分离（两者生命周期刻意分开，见 KnowledgeUploadService 先例）。
 */
@Component
@ConditionalOnProperty(prefix = "annona.resume", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class ResumeStream implements SmartLifecycle {

    public static final String STREAM_KEY = "resume:analyze:stream";
    public static final String GROUP = "resume-analyze-group";

    private final TaskStreamPort taskStreamPort;
    private final ResumeAnalysisService handler;
    private boolean running;

    public ResumeStream(TaskStreamPort taskStreamPort, ResumeAnalysisService handler) {
        this.taskStreamPort = taskStreamPort;
        this.handler = handler;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        String consumerName = "resume-consumer-" + UUID.randomUUID().toString().substring(0, 8);
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

    /** @return 是否投递成功；false = Redis 不可用（恢复调度兜底补投）。 */
    public boolean send(UUID resumeId) {
        return taskStreamPort.send(STREAM_KEY,
            Map.of("resumeId", resumeId.toString(), TaskStreamPort.RETRY_COUNT_FIELD, "0"));
    }
}
