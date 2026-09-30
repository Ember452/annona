package io.annona.modules.interview.orchestrator.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.annona.common.search.Tokenizer;
import io.annona.modules.interview.orchestrator.session.InterviewAnswerRepository;
import io.annona.shared.question.QuestionCandidate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 去重双判的编排分支：向量阈值过滤、关键词全包含判定、实词不足的保护性跳过。
 * 原生 SQL 的向量口径（{@code 1 - (a <=> b)}）与 90 天窗口真库行为由 docker 组
 * InterviewSessionFlowIT 钉死，本 slice 只证 service 侧判定逻辑。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
class QuestionDedupServiceTest {

    @Mock
    private InterviewAnswerRepository answerRepository;

    @Mock
    private Tokenizer tokenizer;

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DIRECTION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private QuestionDedupService newService() {
        return new QuestionDedupService(answerRepository, tokenizer);
    }

    private static QuestionCandidate candidate(int seq, String question) {
        return new QuestionCandidate(
            UUID.nameUUIDFromBytes(("q" + seq).getBytes()), question, 1, 0);
    }

    @Test
    @DisplayName("向量判：SQL 返回行按 PackRules 阈值过滤，低于阈值不算命中")
    void vectorChannelFiltersByThreshold() {
        var near = candidate(1, "Redis 持久化机制");
        var far = candidate(2, "Kafka 控制器 election");
        when(answerRepository.maxCosineToAnswered(eq(USER), eq(DIRECTION), any()))
            .thenReturn(List.of(new Object[]{near.id(), 0.95}, new Object[]{far.id(), 0.61}));
        when(answerRepository.recentAnsweredStems(eq(USER), eq(DIRECTION), any()))
            .thenReturn(List.of());

        var hits = newService().findDedupHits(USER, DIRECTION, List.of(near, far));

        assertThat(hits).containsOnlyKeys(near.id());
        assertThat(hits.get(near.id()).cosine()).isEqualTo(0.95);
        assertThat(hits.get(near.id()).keywordHit()).isFalse();
    }

    @Test
    @DisplayName("关键词判：候选前 5 个实词全部被某历史题干包含才命中")
    void keywordChannelRequiresAllTokens() {
        var dup = candidate(1, "Redis 持久化 机制 RDB AOF 对比");
        when(answerRepository.maxCosineToAnswered(eq(USER), eq(DIRECTION), any()))
            .thenReturn(List.of());
        when(answerRepository.recentAnsweredStems(eq(USER), eq(DIRECTION), any()))
            .thenReturn(List.of("请讲讲 Redis 持久化 机制 RDB AOF 对比 的 生产实践"));
        when(tokenizer.tokenize(dup.question()))
            .thenReturn("redis 持久化 机制 rdb aof 对比");

        var hits = newService().findDedupHits(USER, DIRECTION, List.of(dup));

        assertThat(hits).containsOnlyKeys(dup.id());
        assertThat(hits.get(dup.id()).keywordHit()).isTrue();
    }

    @Test
    @DisplayName("实词不足 5 个的短题干不做关键词判（防一两个通用词误杀）")
    void shortStemSkipsKeywordChannel() {
        var shortStem = candidate(1, "Redis 持久化");
        when(answerRepository.maxCosineToAnswered(eq(USER), eq(DIRECTION), any()))
            .thenReturn(List.of());
        when(answerRepository.recentAnsweredStems(eq(USER), eq(DIRECTION), any()))
            .thenReturn(List.of("Redis 持久化 机制 详解"));
        when(tokenizer.tokenize(shortStem.question())).thenReturn("redis 持久化");

        var hits = newService().findDedupHits(USER, DIRECTION, List.of(shortStem));

        assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("embedding 缺失（SQL 无行）时关键词判独立生效——降级路径")
    void keywordWorksWithoutVectors() {
        var dup = candidate(1, "Kafka 分区 分配器 会话 超时 处理");
        when(answerRepository.maxCosineToAnswered(eq(USER), eq(DIRECTION), any()))
            .thenReturn(List.of());
        when(answerRepository.recentAnsweredStems(eq(USER), eq(DIRECTION), any()))
            .thenReturn(List.of("讲讲 Kafka 分区 分配器 会话 超时 处理"));
        when(tokenizer.tokenize(dup.question()))
            .thenReturn("kafka 分区 分配器 会话 超时 处理");

        var hits = newService().findDedupHits(USER, DIRECTION, List.of(dup));

        assertThat(hits).containsOnlyKeys(dup.id());
        assertThat(hits.get(dup.id()).cosine()).isZero();
    }
}
