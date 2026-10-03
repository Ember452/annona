package io.annona.modules.study.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.study.dto.BlurEventRequest;
import io.annona.modules.study.dto.CheckinResponse;
import io.annona.modules.study.dto.FinishSessionRequest;
import io.annona.modules.study.dto.ManualSessionRequest;
import io.annona.modules.study.dto.SessionResponse;
import io.annona.modules.study.dto.StartSessionRequest;
import io.annona.modules.study.dto.StatsOverviewResponse;
import io.annona.modules.study.dto.UpsertCheckinRequest;
import io.annona.modules.study.service.CheckinService;
import io.annona.modules.study.service.StudySessionService;
import io.annona.modules.study.service.StudyStatsService;
import io.annona.spi.dto.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * study 采集（P1a-04）与统计（P2-01）：番茄钟会话、心跳、事件、手动补录、打卡与年度聚合。
 * 鉴权由 SessionAuthFilter 对 /api/** 强制且白名单不含本路径——未登录即 1004。
 * 心跳是正常高频操作（15s 一次）：P0-05 接入 {@code @RateLimit} 后须豁免本路径（会误伤真实专注），
 * 该注解目前尚未落地（评审 C4）。
 */
@RestController
@RequestMapping("/api/study")
public class StudyController {

    private final StudySessionService sessionService;
    private final CheckinService checkinService;
    private final StudyStatsService statsService;

    public StudyController(StudySessionService sessionService, CheckinService checkinService,
                           StudyStatsService statsService) {
        this.sessionService = sessionService;
        this.checkinService = checkinService;
        this.statsService = statsService;
    }

    @PostMapping("/sessions")
    public Result<SessionResponse> start(@CurrentPrincipal Principal principal,
                                         @RequestBody StartSessionRequest request) {
        return Result.success(sessionService.start(principal.id(), request));
    }

    @PostMapping("/sessions/{id}/heartbeat")
    public Result<Void> heartbeat(@CurrentPrincipal Principal principal,
                                  @PathVariable String id) {
        sessionService.heartbeat(principal.id(), id);
        return Result.success();
    }

    @PostMapping("/sessions/{id}/events")
    public Result<Void> blur(@CurrentPrincipal Principal principal,
                             @PathVariable String id,
                             @RequestBody BlurEventRequest request) {
        sessionService.recordBlur(principal.id(), id, request.type());
        return Result.success();
    }

    @PostMapping("/sessions/{id}/finish")
    public Result<SessionResponse> finish(@CurrentPrincipal Principal principal,
                                          @PathVariable String id,
                                          @RequestBody(required = false) FinishSessionRequest request) {
        boolean abandon = request != null && request.abandon();
        return Result.success(sessionService.finish(principal.id(), id, abandon));
    }

    @PostMapping("/sessions/manual")
    public Result<SessionResponse> manual(@CurrentPrincipal Principal principal,
                                          @RequestBody ManualSessionRequest request) {
        return Result.success(sessionService.createManual(principal.id(), request));
    }

    @GetMapping("/sessions/today")
    public Result<List<SessionResponse>> today(@CurrentPrincipal Principal principal) {
        return Result.success(sessionService.today(principal.id()));
    }

    @PostMapping("/checkins")
    public Result<CheckinResponse> upsertCheckin(@CurrentPrincipal Principal principal,
                                                 @RequestBody UpsertCheckinRequest request) {
        return Result.success(checkinService.upsertToday(principal.id(), request));
    }

    @GetMapping("/checkins/today")
    public Result<CheckinResponse> todayCheckin(@CurrentPrincipal Principal principal) {
        return Result.success(checkinService.today(principal.id()));
    }

    /**
     * 年度统计总览（P2-01）：日聚合 × 质量分桶 + 方向分布。year 缺省取当日年份
     * （AppZones.DAILY 口径）；越界年份 2202。
     */
    @GetMapping("/stats/overview")
    public Result<StatsOverviewResponse> statsOverview(@CurrentPrincipal Principal principal,
                                                       @RequestParam(required = false) Integer year) {
        return Result.success(statsService.overview(UUID.fromString(principal.id()), year));
    }
}
