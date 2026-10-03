package io.annona.modules.plan.service;

import io.annona.modules.plan.entity.PlanTaskEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 拆分 reconcile 纯函数（plan-module-adr §决策 2；形状借 🅢 plan-split 的标题归一化对齐）：
 *
 * <ul>
 *   <li>标题归一化（trim + 小写 + 空白折叠）相同视为同一任务——匹配项只更新内容字段，
 *       status/progress 永不回退；</li>
 *   <li>新增任务插入（source=AI）；</li>
 *   <li>缺失且 PENDING 才删——DONE 的历史不因重拆而丢。</li>
 * </ul>
 *
 * <p>入参 {@link Incoming} 已经过 {@link #normalize} 归一化（上限裁剪 + 非法值兜底），
 * reconcile 本身不做取值判断——两步拆开，测试各管各的。
 */
public final class TaskReconciler {

    /** 归一化后的模型输出任务（字段约束见 ADR §决策 2）。 */
    public record Incoming(String title, String description, String category, String priority,
                           int targetMinutes) {
    }

    /** 匹配项：既有实体 + 对应新内容（服务层据此 update 内容字段）。 */
    public record UpdatePair(PlanTaskEntity task, Incoming incoming) {
    }

    public record Outcome(List<PlanTaskEntity> create, List<UpdatePair> update,
                          List<PlanTaskEntity> delete) {
    }

    private TaskReconciler() {
    }

    public static Outcome reconcile(List<PlanTaskEntity> existing, List<Incoming> incoming) {
        Map<String, Incoming> incomingByKey = new LinkedHashMap<>();
        for (Incoming item : incoming) {
            // 重复标题只留第一条（模型偶发重名时不放大）
            incomingByKey.putIfAbsent(normalizedTitle(item.title()), item);
        }

        List<PlanTaskEntity> create = new ArrayList<>();
        List<UpdatePair> update = new ArrayList<>();
        Set<String> matchedKeys = new HashSet<>();

        for (PlanTaskEntity task : existing) {
            String key = normalizedTitle(task.getTitle());
            Incoming match = incomingByKey.get(key);
            if (match == null) {
                if (PlanTaskEntity.STATUS_PENDING.equals(task.getStatus())) {
                    // 只删缺失且未完成——DONE 是历史（ADR §决策 2）
                    continue;
                }
                matchedKeys.add(key); // 占位防同名新建；DONE 任务保留
                continue;
            }
            matchedKeys.add(key);
            update.add(new UpdatePair(task, match));
        }

        for (Map.Entry<String, Incoming> entry : incomingByKey.entrySet()) {
            if (!matchedKeys.contains(entry.getKey())) {
                // 新建实体预填全部内容字段；planId/userId/directionId 由服务层补归属
                Incoming item = entry.getValue();
                PlanTaskEntity fresh = new PlanTaskEntity();
                fresh.setId(UUID.randomUUID());
                fresh.setTitle(item.title());
                fresh.setDescription(item.description());
                fresh.setCategory(item.category());
                fresh.setPriority(item.priority());
                fresh.setTargetMinutes(item.targetMinutes());
                fresh.setStatus(PlanTaskEntity.STATUS_PENDING);
                fresh.setProgressMinutes(0);
                fresh.setSource(PlanTaskEntity.SOURCE_AI);
                create.add(fresh);
            }
        }
        return new Outcome(create, update, delete(existing, matchedKeys));
    }

    private static List<PlanTaskEntity> delete(List<PlanTaskEntity> existing, Set<String> matchedKeys) {
        List<PlanTaskEntity> delete = new ArrayList<>();
        for (PlanTaskEntity task : existing) {
            if (!matchedKeys.contains(normalizedTitle(task.getTitle()))
                && PlanTaskEntity.STATUS_PENDING.equals(task.getStatus())) {
                delete.add(task);
            }
        }
        return delete;
    }

    /** 标题归一化键：trim + 小写 + 空白折叠（上游同款口径，跨全半角空格对齐）。 */
    public static String normalizedTitle(String title) {
        return title == null ? "" : title.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
