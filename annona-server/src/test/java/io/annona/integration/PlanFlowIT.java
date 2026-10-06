package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.plan.dto.CreatePlanRequest;
import io.annona.modules.plan.dto.CreateTaskRequest;
import io.annona.modules.plan.dto.PlanDetailResponse;
import io.annona.modules.plan.dto.PlanTaskResponse;
import io.annona.modules.plan.service.PlanService;
import io.annona.modules.study.dto.UpsertCheckinRequest;
import io.annona.modules.study.service.CheckinService;
import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.repository.DirectionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * plan 模块在<b>真 PG</b> 上的集测（CI docker-it 专属，本机无 PG 不跑）。
 *
 * <p>钉三件真库才现形的事：① V17 迁移形状（CHECK 约束 + scene 'PLAN' 放宽真实生效）；
 * ② 打卡联动全链路——CheckinService 落库提交后 AFTER_COMMIT 监听器真的把分钟瀑布累计进
 * 方向匹配的任务并自动 DONE（跨 AFTER_COMMIT/REQUIRES_NEW 的事务语义 mock 探不到）；
 * ③ plan 删除级联清任务（V17 ON DELETE CASCADE）。拆分 reconcile 的 LLM 路径不在此测
 * （模型相关，slice 层已覆盖 normalize/reconcile 口径）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("plan 模块真库集测（P2-06）")
class PlanFlowIT {

    @Autowired
    private PlanService planService;
    @Autowired
    private CheckinService checkinService;
    @Autowired
    private DirectionRepository directionRepository;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID userId;
    private UUID directionId;

    @BeforeEach
    void fixture() {
        jdbc = new JdbcTemplate(dataSource);
        userId = UUID.randomUUID();
        jdbc.update("insert into app_user (id, email, password_hash, status, role)"
            + " values (?, ?, 'it-not-a-real-hash', 'ACTIVE', 'USER')",
            userId, "it-plan-" + userId + "@annona.test");
        DirectionEntity direction = new DirectionEntity();
        direction.setId(UUID.randomUUID());
        direction.setUserId(userId);
        direction.setKey("plan-it");
        direction.setName("计划集测方向");
        direction.setOrigin("USER_CUSTOM");
        direction.setStatus("ACTIVE");
        directionRepository.saveAndFlush(direction);
        directionId = direction.getId();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from app_user where id = ?", userId);
    }

    @Test
    @DisplayName("打卡联动全链路：打卡提交后分钟瀑布累计进方向任务并自动 DONE")
    void checkinAccruesIntoDirectionTasks() throws Exception {
        String owner = userId.toString();
        PlanDetailResponse plan = planService.create(owner,
            new CreatePlanRequest("联动集测计划", directionId.toString(), "# 计划\n\n正文"));
        planService.addTask(owner, UUID.fromString(plan.id()), new CreateTaskRequest("任务一", null, 25));
        planService.addTask(owner, UUID.fromString(plan.id()), new CreateTaskRequest("任务二", null, 25));

        // 打卡 2.0h = 120min → 两个 25 分钟任务瀑布吃满（AFTER_COMMIT 监听器异步但同线程同步执行）
        checkinService.upsertToday(owner,
            new UpsertCheckinRequest(directionId.toString(), new BigDecimal("2.0"), null, 3, null, null));

        PlanDetailResponse reloaded = planService.detail(owner, UUID.fromString(plan.id()));
        List<PlanTaskResponse> tasks = reloaded.tasks();
        assertThat(tasks).hasSize(2);
        assertThat(tasks).allSatisfy(task -> {
            assertThat(task.progressMinutes()).isEqualTo(25);
            assertThat(task.status()).isEqualTo("DONE");
        });
    }

    @Test
    @DisplayName("V17 形状：scene 'PLAN' 可入账、非法 category 被约束拒绝")
    void v17Constraints() {
        // PLAN scene（V17 CHECK 放宽后合法）；模型用途列是 purpose（V11），不是 kind
        jdbc.update("insert into token_usage (id, user_id, scene, model, provider, purpose,"
                + " prompt_tokens, completion_tokens) values (?, ?, 'PLAN', 'm', 'fake', 'chat', 1, 1)",
            UUID.randomUUID(), userId);
        Integer planRows = jdbc.queryForObject(
            "select count(*) from token_usage where user_id = ? and scene = 'PLAN'", Integer.class, userId);
        assertThat(planRows).isEqualTo(1);

        // chk_plan_task_category 拒绝越界值
        UUID planId = UUID.randomUUID();
        jdbc.update("insert into plan (id, user_id, title, document) values (?, ?, 't', '')",
            planId, userId);
        try {
            jdbc.update("insert into plan_task (id, plan_id, user_id, title, category)"
                    + " values (?, ?, ?, 'x', 'bogus')",
                UUID.randomUUID(), planId, userId);
            throw new AssertionError("category 越界值应被 chk_plan_task_category 拒绝");
        } catch (Exception expected) {
            // 约束生效即可
        }
    }

    @Test
    @DisplayName("plan 删除级联清任务（V17 ON DELETE CASCADE）")
    void planDeleteCascadesTasks() {
        String owner = userId.toString();
        PlanDetailResponse plan = planService.create(owner,
            new CreatePlanRequest("级联集测", null, "# 计划"));
        planService.addTask(owner, UUID.fromString(plan.id()),
            new CreateTaskRequest("待删任务", null, null));

        Integer before = jdbc.queryForObject(
            "select count(*) from plan_task where plan_id = ?::uuid", Integer.class, plan.id());
        assertThat(before).isEqualTo(1);

        planService.delete(owner, UUID.fromString(plan.id()));
        Integer after = jdbc.queryForObject(
            "select count(*) from plan_task where plan_id = ?::uuid", Integer.class, plan.id());
        assertThat(after).isZero();
    }
}
