package io.annona.modules.usage.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.usage.dto.SessionUsageView;
import io.annona.modules.usage.service.UsageQueryService;
import io.annona.spi.dto.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用量查询端点（P1b-10）：会话成本钻取。聚合与计价在 {@link UsageQueryService}，
 * 本类只做路由与委托。
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageQueryService queryService;

    public UsageController(UsageQueryService queryService) {
        this.queryService = queryService;
    }

    /** GET /session/{id}——该会话按模型聚合的 token 与估算成本（未配价目表时 cost=null）。 */
    @GetMapping("/session/{id}")
    public Result<SessionUsageView> sessionUsage(@CurrentPrincipal Principal principal,
                                                 @PathVariable UUID id) {
        UUID userId = UUID.fromString(principal.id());
        return Result.success(queryService.sessionUsage(userId, id));
    }
}
