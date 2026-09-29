package io.annona.modules.usage.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.usage.config.UsageProperties;
import io.annona.modules.usage.dto.SessionUsageView;
import io.annona.modules.usage.dto.SessionUsageView.UsageItemView;
import io.annona.modules.usage.repository.TokenUsageRepository;
import io.annona.spi.dto.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用量查询端点（P1b-10）：会话成本钻取。同步聚合查询（对计划"queryExecutor"的偏离：
 * 单条 GROUP BY 毫秒级，HTTP 线程本就在 servlet 池；专用读池要跨线程传 SecurityContext，
 * 为一条 SQL 不值——metering ADR 记录）。
 *
 * <p>归属：聚合条件钉 user_id，猜别人的 session_id 只会拿到空视图（200 + items=[]），
 * 不泄漏存在性以外的信息。错误码：无（只读聚合不设业务失败）。
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final TokenUsageRepository repository;
    private final UsageProperties properties;

    public UsageController(TokenUsageRepository repository, UsageProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** GET /session/{id}——该会话按模型聚合的 token 与估算成本。 */
    @GetMapping("/session/{id}")
    public Result<SessionUsageView> sessionUsage(@CurrentPrincipal Principal principal,
                                                 @PathVariable UUID id) {
        UUID userId = UUID.fromString(principal.id());
        Map<String, Double> prices = properties.getModelPrices();
        List<UsageItemView> items = repository.aggregateBySession(id, userId).stream()
            .map(row -> toItem(row, prices))
            .toList();
        return Result.success(new SessionUsageView(id.toString(), items, !prices.isEmpty()));
    }

    private static UsageItemView toItem(Object[] row, Map<String, Double> prices) {
        String model = (String) row[0];
        long prompt = ((Number) row[1]).longValue();
        long completion = ((Number) row[2]).longValue();
        long calls = ((Number) row[3]).longValue();
        Double price = prices.get(model);
        Double cost = price == null ? null
            : (prompt + completion) / 1000.0 * price;
        return new UsageItemView(model, prompt, completion, calls, cost);
    }
}
