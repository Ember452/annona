package io.annona.modules.interview.orchestrator.pack;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.interview.orchestrator.plan.InterviewPlan;
import io.annona.shared.question.QuestionCandidate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 组卷纯逻辑：难度槽位抽取、相邻回填、去重剔除、池内同题干折叠（interview-session-adr
 * §决策 4 的行为规格；阈值本体在 {@link PackRules}）。
 */
@DisplayName("QuestionPackService 组卷算法")
class QuestionPackServiceTest {

    private final QuestionPackService packService = new QuestionPackService();

    private static QuestionCandidate candidate(int seq, int difficulty, String question) {
        return new QuestionCandidate(
            UUID.nameUUIDFromBytes(("q" + seq).getBytes()), question, difficulty, 1);
    }

    @Test
    @DisplayName("足量池按槽位难度精确命中，无缺口")
    void exactDifficultyMatch() {
        var pool = List.of(
            candidate(1, 2, "Redis 持久化机制"),
            candidate(2, 4, "Kafka 分区再平衡"),
            candidate(3, 2, "MySQL 索引失效场景"));
        var plan = new InterviewPlan(2, List.of(2, 4), 0);

        var result = packService.pack(plan, pool, Map.of());

        assertThat(result.questionIds()).containsExactly(pool.get(0).id(), pool.get(1).id());
        assertThat(result.skippedReasons()).isEmpty();
    }

    @Test
    @DisplayName("难度槽位无足量候选时按相邻难度回填，缺口记 skippedReasons")
    void difficultyFallback() {
        var pool = List.of(candidate(1, 1, "q1"), candidate(2, 2, "q2"));
        var plan = new InterviewPlan(3, List.of(5, 5, 5), 0);

        var result = packService.pack(plan, pool, Map.of());

        assertThat(result.questionIds()).hasSize(2);   // 宁缺毋滥：缺 1 题不硬凑
        assertThat(result.skippedReasons()).anyMatch(r -> r.contains("难度5"));
    }

    @Test
    @DisplayName("cosine≥0.92 或关键词全包含的候选被剔除")
    void dedupRejects() {
        var a = candidate(1, 1, "Redis 持久化机制");
        var b = candidate(2, 1, "Kafka 分区 rebalance 流程");   // id=2 命中向量相似
        var c = candidate(3, 1, "Redis 持久化机制详解");         // id=3 命中关键词全包含
        var hits = Map.of(
            b.id(), new StemSimilarity(b.id(), 0.95, false),
            c.id(), new StemSimilarity(c.id(), 0.40, true));
        var plan = new InterviewPlan(2, List.of(1, 1), 0);

        var result = packService.pack(plan, List.of(a, b, c), hits);

        assertThat(result.questionIds()).containsExactly(a.id());
        assertThat(result.skippedReasons()).anyMatch(r -> r.contains("难度1"));
    }

    @Test
    @DisplayName("池内同题干（大小写/空白差异）只取最先一题")
    void sameStemCollapses() {
        var first = candidate(1, 3, "JVM GC 调优");
        var dup = candidate(2, 3, "jvm  gc调优");
        var other = candidate(3, 3, "Spring 事务传播");
        var plan = new InterviewPlan(2, List.of(3, 3), 0);

        var result = packService.pack(plan, List.of(first, dup, other), Map.of());

        assertThat(result.questionIds()).containsExactly(first.id(), other.id());
    }

    @Test
    @DisplayName("复习题豁免历史去重：planner 选定的复习题即便近期答过也必须能进卷（P1c-05）")
    void reviewQuestionIsExemptFromDedup() {
        var fresh = candidate(1, 3, "线程池核心参数设置");
        var review = candidate(2, 3, "AQS 加锁流程");            // 近期答过 → 命中 dedup
        var hits = Map.of(review.id(), new StemSimilarity(review.id(), 0.97, false));
        var plan = new InterviewPlan(2, List.of(3, 3), 0);

        var result = packService.pack(plan, List.of(fresh, review), hits, Set.of(review.id()));

        // 复习题置顶优先，且不被 dedup 静默剔除——否则 REMIND_REVIEW 留痕与实际卷面不符
        assertThat(result.questionIds()).containsExactly(review.id(), fresh.id());
        assertThat(result.skippedReasons()).isEmpty();
    }

    @Test
    @DisplayName("reviewIds 为空时组卷结果与三参重载逐字节一致（接线不改变无决策时的行为）")
    void emptyReviewIdsMatchesThreeArgOverload() {
        var a = candidate(1, 1, "Redis 持久化机制");
        var b = candidate(2, 1, "Kafka 分区再平衡");
        var hits = Map.of(b.id(), new StemSimilarity(b.id(), 0.93, false));
        var plan = new InterviewPlan(2, List.of(1, 1), 0);

        var withEmpty = packService.pack(plan, List.of(a, b), hits, Set.of());
        var legacy = packService.pack(plan, List.of(a, b), hits);

        assertThat(withEmpty.questionIds()).isEqualTo(legacy.questionIds());
        assertThat(withEmpty.skippedReasons()).isEqualTo(legacy.skippedReasons());
        assertThat(withEmpty.questionIds()).containsExactly(a.id());
    }

    @Test
    @DisplayName("同题不重复占两个槽位，回填后池尽则止")
    void questionUsedOnce() {
        var only = candidate(1, 1, "唯一题");
        var plan = new InterviewPlan(3, List.of(1, 1, 1), 0);

        var result = packService.pack(plan, List.of(only), Map.of());

        assertThat(result.questionIds()).containsExactly(only.id());
        assertThat(result.skippedReasons()).hasSize(1);   // 同难度缺口聚合为一条
        assertThat(result.skippedReasons().get(0)).contains("难度1").contains("跳过 2 个槽位");
    }
}
