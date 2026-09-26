package io.annona.shared.direction.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.shared.direction.dto.BindKbDocRequest;
import io.annona.shared.direction.dto.CreateDirectionRequest;
import io.annona.shared.direction.dto.DirectionResponse;
import io.annona.shared.direction.service.DirectionCommandService;
import io.annona.shared.direction.service.DirectionQueryService;
import io.annona.spi.dto.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 方向字典（P1a-03）：下拉取数 / 即时新建 / 归档 / 绑定知识库。
 * 鉴权由 SessionAuthFilter 对 /api/** 强制且白名单不含本路径——未登录即 1004。
 */
@RestController
@RequestMapping("/api/directions")
public class DirectionController {

    private final DirectionQueryService queryService;
    private final DirectionCommandService commandService;

    public DirectionController(DirectionQueryService queryService,
                               DirectionCommandService commandService) {
        this.queryService = queryService;
        this.commandService = commandService;
    }

    @GetMapping
    public Result<List<DirectionResponse>> list(@CurrentPrincipal Principal principal) {
        return Result.success(queryService.listVisible(principal.id()));
    }

    @PostMapping
    public Result<DirectionResponse> create(@CurrentPrincipal Principal principal,
                                            @RequestBody CreateDirectionRequest request) {
        return Result.success(commandService.create(principal.id(), request));
    }

    @PostMapping("/{id}/archive")
    public Result<Void> archive(@CurrentPrincipal Principal principal, @PathVariable String id) {
        commandService.archive(principal.id(), id);
        return Result.success();
    }

    @PutMapping("/{id}/kb-doc")
    public Result<DirectionResponse> bindKbDoc(@CurrentPrincipal Principal principal,
                                               @PathVariable String id,
                                               @RequestBody BindKbDocRequest request) {
        return Result.success(commandService.bindKbDoc(principal.id(), id, request.kbDocId()));
    }
}
