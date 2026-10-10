package io.annona.modules.voice.tts;

import io.annona.common.voice.TtsOptions;
import io.annona.common.voice.TtsProvider;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 句子级并发 TTS 有序发射器（P3-03，🅖 OrderedTtsChunkEmitter 同构）：
 * {@code submit(句子)} 按到达序取得序号并受信号量限并发地异步合成；发射线程严格按
 * 序号等待并逐块下发——<b>首句优先</b>（第 1 句合成完立即播，不等后续），边合成边播。
 *
 * <p>并发形状硬约束（2026-10-10 实测死锁后定下）：<b>submit 不得阻塞调用线程</b>，许可在
 * 异步任务内取。调用方（doSpeakTurn）与排空线程、各句合成都跑在同一个 ai-io 池上，
 * 只要 submit 在调用线程上等许可，就存在“池满→合成任务排不上队→submit 永不返回”的
 * 自锁链；改为任务入队后自行取许可，满时句子停在队列里，退化为“超时跳句”而不是死锁。
 * 因此池必须至少能同时跑“1 个排空线程 + maxConcurrent 个合成”（生产 aiIoExecutor 与
 * 单测都按多线池配）。
 *
 * <p>失败语义：单句合成失败/超时只跳过该句（序号继续），已播出句子保留——口语场景
 * 掉一句比整段沉默可接受（🅖 同口径）；句文本与音频的差错都只 warn，不上抛。
 * {@link #finish()} 由流终态调用；{@link #awaitCompletion(long)} 阻塞到全部发射完成
 * 或超时（返回已发射块数）。本类非线程安全的 close 语义：一次性使用，不复位。
 */
public class OrderedTtsChunkEmitter {

    /** 下发回调：seq 单调递增；wav 为封装好的可播音频；isLast=true 标记本批末块。 */
    public interface Sink {
        void emit(int seq, byte[] wav, boolean isLast);
    }

    private static final Logger log = LoggerFactory.getLogger(OrderedTtsChunkEmitter.class);

    private final TtsProvider tts;
    private final TtsOptions options;
    private final Semaphore permits;
    private final long chunkTimeoutSec;
    private final Sink sink;
    private final Executor executor;

    private final Map<Integer, CompletableFuture<byte[]>> futures = new ConcurrentHashMap<>();
    private final AtomicInteger submittedIndex = new AtomicInteger();
    private final AtomicInteger emittedCount = new AtomicInteger();
    private final Object lock = new Object();
    private final CompletableFuture<Integer> drainFuture;
    private volatile int totalChunks = -1;

    public OrderedTtsChunkEmitter(TtsProvider tts, TtsOptions options, int maxConcurrent,
                                  long chunkTimeoutSec, Sink sink, Executor executor) {
        this.tts = tts;
        this.options = options;
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
        this.chunkTimeoutSec = chunkTimeoutSec;
        this.sink = sink;
        this.executor = executor;
        // 排空线程常驻 wait(100) 轮询直到 finish()——占用一个池线程直到本轮语音结束
        // （🅖 同款取舍；批 3 若池紧张再改事件驱动）
        this.drainFuture = CompletableFuture.supplyAsync(this::drain, executor);
    }

    /**
     * 提交一句合成：取序号后异步入队，<b>调用线程不等许可</b>（并发上限由任务内部的
     * 信号量守住；在此阻塞会把 ai-io 池带进自锁，见类注）。单轮句子数由 LLM 输出
     * 天然有界（面试官轮 2-4 句），不必另做上游反压。
     */
    public void submit(String sentence) {
        int index = submittedIndex.getAndIncrement();
        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
            permits.acquireUninterruptibly();
            try {
                return tts.synthesize(sentence, options);
            } finally {
                permits.release();
            }
        }, executor);
        futures.put(index, future);
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** 流终态：之后不再有新句子，发射线程排空后自行结束。 */
    public void finish() {
        synchronized (lock) {
            totalChunks = submittedIndex.get();
            lock.notifyAll();
        }
    }

    /** 阻塞排空（超时 = 按 (超时+1)×句数 估总预算）；返回成功下发的块数。 */
    public int awaitCompletion() {
        long budgetSec = Math.max(chunkTimeoutSec + 2, (chunkTimeoutSec + 1) * Math.max(1, submittedIndex.get()));
        try {
            return drainFuture.get(budgetSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[voice-tts] emitter budget {}s exceeded", budgetSec);
            return emittedCount.get();
        } catch (Exception e) {
            log.warn("[voice-tts] emitter interrupted: {}", e.toString());
            Thread.currentThread().interrupt();
            return emittedCount.get();
        }
    }

    private int drain() {
        int index = 0;
        while (true) {
            CompletableFuture<byte[]> future = waitForFuture(index);
            if (future == null) {
                sink.emit(index, new byte[0], true);
                return emittedCount.get();
            }
            try {
                byte[] pcm = future.get(chunkTimeoutSec, TimeUnit.SECONDS);
                if (pcm != null && pcm.length > 0) {
                    sink.emit(index, pcm, false);
                    emittedCount.incrementAndGet();
                }
            } catch (TimeoutException e) {
                log.warn("[voice-tts] chunk {} timed out, skipped", index);
            } catch (Exception e) {
                log.warn("[voice-tts] chunk {} failed, skipped: {}", index, e.toString());
            } finally {
                futures.remove(index);
                index++;
            }
        }
    }

    /** 等待指定序号的合成结果；finish 后越界返回 null（排空结束）。中断按排空结束处理。 */
    private CompletableFuture<byte[]> waitForFuture(int index) {
        synchronized (lock) {
            while (!futures.containsKey(index)) {
                if (totalChunks >= 0 && index >= totalChunks) {
                    return null;
                }
                try {
                    lock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return futures.get(index);
        }
    }
}
