package io.annona.modules.study.controller;

import io.annona.common.presence.PresencePort;
import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.study.dto.PresenceResponse;
import io.annona.spi.dto.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匿名共学状态（P2-05）：GET /api/study/presence → {online}。
 *
 * <p>「只读接口」的一个有意例外：本端点在读取同时刷新调用者自己的在场（轮询即心跳）——
 * 一次请求完成「我在 + 有谁在」，省掉独立的写端点；这是读接口带副作用的取舍，
 * 换来无房间、无消息通道、前端 10s 轮询即可。门控 annona.presence.enabled（缺省开）。
 */
@RestController
@ConditionalOnProperty(value = "annona.presence.enabled", havingValue = "true", matchIfMissing = true)
public class PresenceController {

    /** 在场窗口：前端 10s 轮询，容忍连续 4 次丢包（45s）不掉线。 */
    static final Duration ACTIVE_WINDOW = Duration.ofSeconds(45);

    private final PresencePort presence;

    public PresenceController(PresencePort presence) {
        this.presence = presence;
    }

    @GetMapping("/api/study/presence")
    public Result<PresenceResponse> presence(@CurrentPrincipal Principal principal) {
        Instant now = Instant.now();
        presence.touch(UUID.fromString(principal.id()), now);
        return Result.success(new PresenceResponse(presence.activeCount(now, ACTIVE_WINDOW)));
    }
}
