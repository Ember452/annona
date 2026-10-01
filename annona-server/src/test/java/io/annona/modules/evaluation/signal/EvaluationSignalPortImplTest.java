package io.annona.modules.evaluation.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.annona.modules.evaluation.repository.InterviewEvaluationRepository;
import io.annona.modules.evaluation.repository.InterviewReportRepository;
import io.annona.spi.dto.SessionOutcome;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("EvaluationSignalPortImpl 原生 JOIN 行映射")
class EvaluationSignalPortImplTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DIR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final int LIMIT = 10;

    @Mock
    private InterviewReportRepository repository;
    @Mock
    private InterviewEvaluationRepository evaluationRepository;

    private EvaluationSignalPortImpl impl() {
        return new EvaluationSignalPortImpl(repository, evaluationRepository);
    }

    @Test
    @DisplayName("正常行：八列按序映射，compositeScore 由 Short 转 Integer")
    void mapsFullRow() {
        Instant finished = Instant.parse("2026-09-20T08:00:00Z");
        when(repository.findLatestDoneOutcomes(USER, DIR, LIMIT)).thenReturn(List.<Object[]>of(
            new Object[]{"s1", DIR.toString(), Timestamp.from(finished), (short) 82,
                "chat-m", "eval-m", "hash123", "v2"}));

        List<SessionOutcome> outcomes = impl().latestOutcomes(USER, DIR, LIMIT);

        assertThat(outcomes).hasSize(1);
        SessionOutcome o = outcomes.get(0);
        assertThat(o.sessionId()).isEqualTo("s1");
        assertThat(o.directionId()).isEqualTo(DIR.toString());
        assertThat(o.compositeScore()).isEqualTo(82);
        assertThat(o.finishedAt()).isEqualTo(finished);
        assertThat(o.evaluatorVersion()).isEqualTo("v2");
    }

    @Test
    @DisplayName("降级场：compositeScore 与 finished_at 皆 null，映射不 NPE")
    void mapsNullScoreAndTimestamp() {
        when(repository.findLatestDoneOutcomes(USER, DIR, LIMIT)).thenReturn(List.<Object[]>of(
            new Object[]{"s2", DIR.toString(), null, null, null, null, null, "v2"}));

        List<SessionOutcome> outcomes = impl().latestOutcomes(USER, DIR, LIMIT);

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).compositeScore()).isNull();
        assertThat(outcomes.get(0).finishedAt()).isNull();
    }

    @Test
    @DisplayName("无 DONE 报告：空列表")
    void emptyWhenNoReports() {
        when(repository.findLatestDoneOutcomes(USER, DIR, LIMIT)).thenReturn(List.of());

        assertThat(impl().latestOutcomes(USER, DIR, LIMIT)).isEmpty();
    }

    @Test
    @DisplayName("最低分题：limit<=0 直接空集；否则委托仓库")
    void weakestQuestions() {
        assertThat(impl().weakestQuestionIds(USER, DIR, 0)).isEmpty();
        UUID q = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        when(evaluationRepository.findWeakestQuestionIds(eq(USER), eq(DIR), eq(3)))
            .thenReturn(List.of(q));
        assertThat(impl().weakestQuestionIds(USER, DIR, 3)).containsExactly(q);
    }
}
