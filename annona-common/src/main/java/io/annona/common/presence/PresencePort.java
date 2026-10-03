package io.annona.common.presence;

import java.time.Instant;
import java.util.UUID;

/**
 * 匿名共学在场端口（P2-05）：谁在线只以计数暴露——无身份、无房间、无消息通道。
 *
 * <p>实现方约定：{@code touch} 幂等刷新某用户的最后在场时刻；{@code activeCount}
 * 返回窗口内有心跳的人数，并顺手清掉窗口外的陈旧成员（滑窗语义，读时剪枝）。
 * 实现放 infrastructure（Redis ZSET），端口进 common 供业务模块消费。
 */
public interface PresencePort {

    /** 刷新用户在场时刻（幂等；同一毫秒重复调用无副作用）。 */
    void touch(UUID userId, Instant at);

    /** 窗口内活跃人数（at 视角往前 window 时长内有 touch 的去重用户数）。 */
    long activeCount(Instant at, java.time.Duration window);
}
