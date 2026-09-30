package io.annona.modules.usage.service;

import io.annona.modules.usage.config.UsageProperties;
import io.annona.modules.usage.dto.SessionUsageView;
import io.annona.modules.usage.dto.SessionUsageView.UsageItemView;
import io.annona.modules.usage.repository.TokenUsageRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 用量查询（读侧）：会话成本钻取的聚合与计价。与写侧 {@link UsageRecorder} 分离——
 * 记账走 afterCommit 异步，本类是同步只读聚合，计价公式只此一处（原在控制器内，
 * 2026-09-30 质量扫描下沉）。
 */
@Service
public class UsageQueryService {

    private final TokenUsageRepository repository;
    private final UsageProperties properties;

    public UsageQueryService(TokenUsageRepository repository, UsageProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 会话成本钻取：按模型聚合 token 并按价目表估算成本。
     *
     * <p>前置条件：{@code userId} 来自已鉴权 Principal。失败语义：无业务失败——只读
     * 聚合不设错误码；非 owner 的 sessionId 命不中聚合条件，返回 items=[]（200），
     * 不泄露存在性以外的信息（归属谓词钉 user_id）。事务边界：单条 GROUP BY 只读
     * SQL，不开事务；同步执行对计划"queryExecutor"的偏离——单条聚合毫秒级，HTTP
     * 线程本就在 servlet 池，专用读池要跨线程传 SecurityContext，为一条 SQL 不值
     * （metering ADR 记录）。
     *
     * <p>计价口径：价目表缺该模型 → {@code estimatedCost=null}（宁可不显示，不给
     * 假数），配合 {@link SessionUsageView#priced()} 提示前端"未配置单价"。
     */
    public SessionUsageView sessionUsage(UUID userId, UUID sessionId) {
        Map<String, Double> prices = properties.getModelPrices();
        List<UsageItemView> items = repository.aggregateBySession(sessionId, userId).stream()
            .map(row -> toItem(row, prices))
            .toList();
        return new SessionUsageView(sessionId.toString(), items, !prices.isEmpty());
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
