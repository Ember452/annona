package io.annona.config.async;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * 原生线程池的统一优雅关闭（P0-05 补齐）。
 *
 * <p>问题：{@code @Bean(destroyMethod = "shutdown")} 只调 {@code shutdown()} 就返回，
 * 不等在飞任务结束。对 annona 这不是纯洁癖——异步评估回写、ETL 向量化这类任务
 * 一旦在关闭时被丢弃，就会留下"会话已交卷但无评估结果"的半成品状态，而这类状态
 * 只能靠人工排查（决策留痕表里会有 trace 却没有结果）。
 *
 * <p>为什么单独一个 Bean 而不是在各池上处理：Spring 按依赖逆序销毁 bean，
 * 本组件依赖 {@code List<ExecutorService>}（即那三个原生池；{@code generalExecutor}
 * 是 {@code ThreadPoolTaskExecutor}，不在其中，它自己已配 awaitTermination），
 * 因此销毁时本组件先执行，能一次性管好全部池。
 *
 * <p>顺序：先 {@code shutdown()} 拒新任务 → 等 {@value #AWAIT_SECONDS}s →
 * 超时则 {@code shutdownNow()} 并把未完成数打进日志（不是静默丢弃）。
 */
@Component
public class AnnonaExecutorShutdown implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AnnonaExecutorShutdown.class);
    static final int AWAIT_SECONDS = 30;

    private final List<ExecutorService> executors;

    public AnnonaExecutorShutdown(List<ExecutorService> executors) {
        this.executors = executors;
    }

    @Override
    public void destroy() {
        for (ExecutorService executor : executors) {
            executor.shutdown();
        }
        for (ExecutorService executor : executors) {
            try {
                if (!executor.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    int pending = executor.shutdownNow().size();
                    log.warn("线程池未在 {}s 内收尾，强制停止；未完成任务约 {} 个",
                        AWAIT_SECONDS, pending);
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                log.warn("等待线程池收尾被中断，已强制停止");
            }
        }
    }
}
