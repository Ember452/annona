package io.annona.infrastructure.stream;

import io.annona.common.stream.TaskStreamPort;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.redisson.api.PendingEntry;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@link TaskStreamPort} 的 Redisson 实现（借 🅖 AbstractStreamProducer/Consumer 语义）。
 *
 * <p>消费循环：阻塞读新消息（{@code >}，只读未投递过的）+ 周期性认领 pending
 * （实例崩溃后遗留的未 ACK 消息，空闲超阈值即接管重放）。处理结论三态：
 * ACK 确认 / RETRY 重投（retryCount+1 后 ACK 原消息）/ DEAD 确认丢弃（业务已判死）。
 * 每个消费者一个守护线程，应用停机时中断（{@link PreDestroy}）。
 *
 * <p>流长裁剪 maxlen≈1000（借 🅖 STREAM_MAX_LEN）：任务流只承载瘦消息（ID + retryCount），
 * 裁剪丢失的未处理消息由 DB 侧恢复调度兜底（PENDING 超时补投），不依赖流内留存。
 */
@Component
public class RedissonTaskStream implements TaskStreamPort {

    private static final Logger log = LoggerFactory.getLogger(RedissonTaskStream.class);

    /** 流长近似裁剪（借 🅖 STREAM_MAX_LEN=1000）。 */
    private static final int STREAM_MAX_LEN = 1000;

    /** pending 认领扫描的间隔（借 🅖：pending 空闲阈值 5min，扫描每 30s 足够）。 */
    private static final long PENDING_SWEEP_INTERVAL_MILLIS = 30_000;

    private final RedissonClient redisson;
    private final List<Thread> consumerThreads = new CopyOnWriteArrayList<>();

    public RedissonTaskStream(@Lazy RedissonClient redissonClient) {
        this.redisson = redissonClient;
    }

    @Override
    public boolean send(String streamKey, Map<String, String> payload) {
        try {
            Map<String, String> withRetry = new LinkedHashMap<>(payload);
            withRetry.putIfAbsent(RETRY_COUNT_FIELD, "0");
            redisson.<String, String>getStream(streamKey)
                .add(StreamAddArgs.entries(withRetry).trimNonStrict().maxLen(STREAM_MAX_LEN).noLimit());
            return true;
        } catch (RuntimeException e) {
            log.warn("任务流投递失败 streamKey={}", streamKey, e);
            return false;
        }
    }

    @Override
    public void consume(ConsumerSpec spec, TaskMessageHandler handler) {
        Thread thread = Thread.ofPlatform()
            .name("task-stream-" + spec.group())
            .daemon(true)
            .start(() -> runLoop(spec, handler));
        consumerThreads.add(thread);
    }

    @PreDestroy
    void stopConsumers() {
        consumerThreads.forEach(Thread::interrupt);
    }

    private void runLoop(ConsumerSpec spec, TaskMessageHandler handler) {
        RStream<String, String> stream = redisson.getStream(spec.streamKey());
        ensureGroup(stream, spec);
        long lastPendingSweep = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                long now = System.nanoTime();
                if (now - lastPendingSweep >= PENDING_SWEEP_INTERVAL_MILLIS * 1_000_000L) {
                    lastPendingSweep = now;
                    Map<StreamMessageId, Map<String, String>> claimed = claimPending(stream, spec);
                    if (!claimed.isEmpty()) {
                        processAll(stream, spec, handler, claimed);
                        continue;
                    }
                }
                Map<StreamMessageId, Map<String, String>> fresh = stream.readGroup(
                    spec.group(), spec.consumerName(),
                    StreamReadGroupArgs.greaterThan(StreamMessageId.NEVER_DELIVERED)
                        .count(spec.batchSize())
                        .timeout(Duration.ofMillis(spec.pollIntervalMs())));
                if (fresh != null && !fresh.isEmpty()) {
                    processAll(stream, spec, handler, fresh);
                }
            } catch (RuntimeException e) {
                if (causedByInterrupt(e)) {
                    // 关停路径：@PreDestroy 用 interrupt 停消费者，而 CompletableFuture.get() 抛
                    // InterruptedException 时会把中断标志清掉，循环条件就再也挡不住自己：
                    // 不在此退出会在上下文销毁期间再取一条消息去处理（JPA/事务 bean 已关），
                    // 并把正常关停记成一条吓人的“消费异常”。
                    Thread.currentThread().interrupt();
                    log.info("消费线程被中断，停止消费 streamKey={} group={}",
                        spec.streamKey(), spec.group());
                    return;
                }
                log.warn("任务流消费异常 streamKey={} group={}，{}ms 后重试",
                    spec.streamKey(), spec.group(), spec.pollIntervalMs(), e);
                sleepQuietly(spec.pollIntervalMs());
            }
        }
    }

    /**
     * 异常链里是否含 {@link InterruptedException}（Redisson 会把它包成 {@code RedisException}）。
     *
     * <p>包可见：这是关停语义的判据，有单测守住包装层级变化（升级 Redisson 后包结构一变，
     * 判据失效就会退回“关停时刷错误日志”的老行为）。
     */
    static boolean causedByInterrupt(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            if (cause.getCause() == cause) {
                // 自引用的异常（少见但存在），不跳出就会死循环
                return false;
            }
        }
        return false;
    }

    private void ensureGroup(RStream<String, String> stream, ConsumerSpec spec) {
        try {
            stream.createGroup(StreamCreateGroupArgs.name(spec.group())
                .id(new StreamMessageId(0, 0))
                .makeStream());
        } catch (RuntimeException e) {
            // BUSYGROUP = 组已存在（重启）；key 不存在时 makeStream 应已兜底，其余异常交给循环重试
            log.debug("消费组创建跳过 streamKey={} group={}：{}", spec.streamKey(), spec.group(), e.getMessage());
        }
    }

    private Map<StreamMessageId, Map<String, String>> claimPending(RStream<String, String> stream, ConsumerSpec spec) {
        List<PendingEntry> pending = stream.listPending(spec.group(), StreamMessageId.MIN,
            StreamMessageId.MAX, spec.batchSize());
        StreamMessageId[] stale = pending.stream()
            .filter(entry -> entry.getIdleTime() >= spec.pendingIdleMs())
            .map(PendingEntry::getId)
            .toArray(StreamMessageId[]::new);
        if (stale.length == 0) {
            return Map.of();
        }
        return stream.claim(spec.group(), spec.consumerName(), spec.pendingIdleMs(),
            TimeUnit.MILLISECONDS, stale);
    }

    private void processAll(RStream<String, String> stream, ConsumerSpec spec,
        TaskMessageHandler handler, Map<StreamMessageId, Map<String, String>> messages) {
        for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
            StreamMessageId id = entry.getKey();
            Map<String, String> payload = entry.getValue() == null ? Map.of() : entry.getValue();
            int retryCount = parseRetryCount(payload);
            Outcome outcome;
            try {
                outcome = handler.handle(String.valueOf(id), payload, retryCount);
            } catch (RuntimeException e) {
                log.warn("消息处理抛出异常，按可重试处理 streamKey={} msgId={}", spec.streamKey(), id, e);
                outcome = Outcome.RETRY;
            }
            switch (outcome) {
                case ACK, DEAD -> stream.ack(spec.group(), id);
                case RETRY -> {
                    Map<String, String> withRetry = new LinkedHashMap<>(payload);
                    withRetry.put(RETRY_COUNT_FIELD, String.valueOf(retryCount + 1));
                    send(spec.streamKey(), withRetry);
                    stream.ack(spec.group(), id);
                }
                default -> stream.ack(spec.group(), id);
            }
        }
    }

    private static int parseRetryCount(Map<String, String> payload) {
        String value = payload.get(RETRY_COUNT_FIELD);
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
