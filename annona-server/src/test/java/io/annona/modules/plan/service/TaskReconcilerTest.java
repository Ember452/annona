package io.annona.modules.plan.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.plan.entity.PlanTaskEntity;
import io.annona.modules.plan.service.TaskReconciler.Incoming;
import io.annona.modules.plan.service.TaskReconciler.Outcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TaskReconciler} 黄金口径（plan-module-adr §决策 2）：标题归一化匹配、
 * 内容更新保状态、新建补齐、缺失且 PENDING 才删。该算法直接决定用户任务是否被
 * 误删/误重复，边界全部钉死。
 */
@DisplayName("拆分 reconcile 黄金口径")
class TaskReconcilerTest {

    private PlanTaskEntity task(String title, String status, int progress) {
        PlanTaskEntity entity = new PlanTaskEntity();
        entity.setId(UUID.randomUUID());
        entity.setTitle(title);
        entity.setStatus(status);
        entity.setProgressMinutes(progress);
        entity.setTargetMinutes(25);
        entity.setSource(PlanTaskEntity.SOURCE_AI);
        return entity;
    }

    private Incoming incoming(String title) {
        return new Incoming(title, "desc", "study", "normal", 30);
    }

    @Test
    @DisplayName("标题归一化：空白/大小写差异视为同一任务，不误删不重复建")
    void normalizedTitleMatching() {
        PlanTaskEntity existing = task("  学习   JVM  ", PlanTaskEntity.STATUS_PENDING, 0);
        Outcome outcome = TaskReconciler.reconcile(
            List.of(existing), List.of(incoming("学习 JVM")));

        assertThat(outcome.update()).hasSize(1);
        assertThat(outcome.create()).isEmpty();
        assertThat(outcome.delete()).isEmpty();
        assertThat(TaskReconciler.normalizedTitle("  A  B ")).isEqualTo("a b");
    }

    @Test
    @DisplayName("匹配项只更新内容字段，status/progress 保留")
    void updateKeepsStatusAndProgress() {
        PlanTaskEntity existing = task("读第一章", PlanTaskEntity.STATUS_DONE, 20);
        Outcome outcome = TaskReconciler.reconcile(
            List.of(existing), List.of(new Incoming("读第一章", "新描述", "review", "high", 40)));

        assertThat(outcome.update()).hasSize(1);
        assertThat(outcome.update().get(0).incoming().description()).isEqualTo("新描述");
        assertThat(outcome.update().get(0).incoming().targetMinutes()).isEqualTo(40);
        // 实际字段落库由服务层 apply——reconcile 的契约是"给了更新对"，实体未被改动
        assertThat(existing.getStatus()).isEqualTo(PlanTaskEntity.STATUS_DONE);
        assertThat(existing.getProgressMinutes()).isEqualTo(20);
    }

    @Test
    @DisplayName("缺失且 PENDING → 删除；缺失但 DONE → 保留")
    void deleteOnlyMissingPending() {
        PlanTaskEntity pending = task("旧任务A", PlanTaskEntity.STATUS_PENDING, 5);
        PlanTaskEntity done = task("旧任务B", PlanTaskEntity.STATUS_DONE, 25);
        Outcome outcome = TaskReconciler.reconcile(
            List.of(pending, done), List.of(incoming("全新任务")));

        assertThat(outcome.delete()).containsExactly(pending);
        assertThat(outcome.create()).hasSize(1);
        assertThat(outcome.create().get(0).getTitle()).isEqualTo("全新任务");
        // 新建实体已预填内容与状态（服务层只补归属）
        assertThat(outcome.create().get(0).getStatus()).isEqualTo(PlanTaskEntity.STATUS_PENDING);
        assertThat(outcome.create().get(0).getTargetMinutes()).isEqualTo(30);
        assertThat(outcome.create().get(0).getSource()).isEqualTo(PlanTaskEntity.SOURCE_AI);
    }

    @Test
    @DisplayName("模型输出重复标题只建一条（不放大）")
    void duplicateTitlesCollapse() {
        Outcome outcome = TaskReconciler.reconcile(
            List.of(), List.of(incoming("同一任务"), incoming("同一任务")));

        assertThat(outcome.create()).hasSize(1);
    }
}
