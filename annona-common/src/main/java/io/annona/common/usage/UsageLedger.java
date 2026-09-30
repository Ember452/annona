package io.annona.common.usage;

import java.util.UUID;

/**
 * 用量记账端口（llmprovider-metering-adr 批 3 修订）：模型出口的记账能力对业务模块的
 * 可见形状。同步 chat 由 {@code MeteredModelProvider} 装饰器单点记账；流式 chat 与
 * embedding 不经该装饰器（端口不同、异步线程拿不到请求 ThreadLocal），由调用点在
 * 执行线程 bind {@link UsageContext} 后调本端口显式记账。
 *
 * <p>为什么是 common 端口而非直连 modules/usage 的 UsageRecorder：依赖方向
 * {@code modules → spi → common}，qa/knowledge/questionbank 直接 import 另一个业务模块
 * 的写服务违反 AGENTS §4（跨模块只读走 QueryService/shared，写走事件）。记账是横切基础
 * 能力，端口沉到 common 让各模块经它注入，实现（异步、afterCommit、丢帧容忍）留在 usage 模块。
 */
public interface UsageLedger {

    /**
     * 记一笔模型用量。实现异步落库、不进调用方事务、失败只 warn；{@code userId==null}
     * （无归属上下文）时实现方安静跳过。
     */
    void record(UsageEntry entry);

    /** 一次调用的完整账目（字段语义 = token_usage 列，V11 注释为准）。 */
    record UsageEntry(UUID userId, String scene, UUID sessionId, String provider,
                      String model, String purpose, int promptTokens, int completionTokens,
                      String promptHash, String evaluatorVersion) {
    }
}
