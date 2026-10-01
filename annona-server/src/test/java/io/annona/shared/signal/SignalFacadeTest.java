package io.annona.shared.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.annona.shared.evaluation.EvaluationSignalPort;
import io.annona.shared.study.StudySignal;
import io.annona.shared.study.StudySignalPort;
import io.annona.spi.dto.DirectionSignal;
import io.annona.spi.dto.SessionOutcome;
import io.annona.spi.dto.SignalSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SignalFacade（P1c-01）：组合学习侧与面试侧端口产出方向感知快照。断言三件事——
 * 同用户同日同方向可复现、方向无学习记录=学习侧空但面试侧有值（正常形态）、
 * avgScore 跳过降级场（null 分）。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("SignalFacade 方向信号聚合")
class SignalFacadeTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DIR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Mock
    private ObjectProvider<StudySignalPort> studyProviders;
    @Mock
    private ObjectProvider<EvaluationSignalPort> evalProviders;
    @Mock
    private StudySignalPort studyPort;
    @Mock
    private EvaluationSignalPort evalPort;

    private SignalFacade facade() {
        return new SignalFacade(studyProviders, evalProviders);
    }

    @Test
    @DisplayName("相交方向：学习时长与面试结果都在快照里，avgScore 跳过降级场")
    void directionalComposesBothSides() {
        when(studyProviders.getIfAvailable()).thenReturn(studyPort);
        when(evalProviders.getIfAvailable()).thenReturn(evalPort);
        when(studyPort.studySignal(USER, DIR, FROM, TO))
            .thenReturn(new StudySignal(Duration.ofMinutes(120), Duration.ofMinutes(30)));
        when(evalPort.recentOutcomes(USER, DIR, FROM, TO)).thenReturn(List.of(
            outcome("s1", 80, Instant.parse("2026-09-20T00:00:00Z")),
            outcome("s2", null, Instant.parse("2026-09-25T00:00:00Z")), // 降级场
            outcome("s3", 60, Instant.parse("2026-09-10T00:00:00Z"))));

        SignalSnapshot snap = facade().readDirectional(USER.toString(), DIR, FROM, TO);

        assertThat(snap.sampleSize()).isEqualTo(3);
        assertThat(snap.directionals()).hasSize(1);
        DirectionSignal d = snap.directionals().get(0);
        assertThat(d.directionId()).isEqualTo(DIR.toString());
        assertThat(d.sessions()).isEqualTo(3);
        // 非 null 分 [80,60] 均值 = 70.0；降级场不计入
        assertThat(d.avgScore()).isEqualTo(70.0);
        // 最近交卷 = 最新 finishedAt（倒序首条 s2 的 finishedAt 是最新）
        assertThat(d.lastPracticedAt()).isEqualTo(Instant.parse("2026-09-25T00:00:00Z"));
        assertThat(d.verifiedStudyMinutes()).isEqualTo(Duration.ofMinutes(120));
        assertThat(d.selfReportedMinutes()).isEqualTo(Duration.ofMinutes(30));
        assertThat(d.hasStudyRecord()).isTrue();
    }

    @Test
    @DisplayName("方向不相交：学习侧全零但面试侧照常，是正常形态非降级")
    void noStudyRecordIsNormalShape() {
        when(studyProviders.getIfAvailable()).thenReturn(studyPort);
        when(evalProviders.getIfAvailable()).thenReturn(evalPort);
        when(studyPort.studySignal(USER, DIR, FROM, TO))
            .thenReturn(new StudySignal(Duration.ZERO, Duration.ZERO));
        when(evalPort.recentOutcomes(USER, DIR, FROM, TO)).thenReturn(List.of(
            outcome("s1", 75, Instant.parse("2026-09-20T00:00:00Z"))));

        SignalSnapshot snap = facade().readDirectional(USER.toString(), DIR, FROM, TO);

        DirectionSignal d = snap.directionals().get(0);
        assertThat(d.hasStudyRecord()).isFalse();
        assertThat(d.sessions()).isEqualTo(1);
        assertThat(d.avgScore()).isEqualTo(75.0);
        assertThat(snap.sampleSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("全部门关（端口 bean 缺席）：降级为面试/学习皆空，不抛异常")
    void absentPortsYieldEmptySnapshot() {
        when(studyProviders.getIfAvailable()).thenReturn(null);
        when(evalProviders.getIfAvailable()).thenReturn(null);

        SignalSnapshot snap = facade().readDirectional(USER.toString(), DIR, FROM, TO);

        assertThat(snap.sampleSize()).isZero();
        assertThat(snap.directionals()).hasSize(1);
        assertThat(snap.directionals().get(0).avgScore()).isNull();
    }

    @Test
    @DisplayName("可复现：同输入两次读取产出结构相等的快照")
    void reproducible() {
        when(studyProviders.getIfAvailable()).thenReturn(studyPort);
        when(evalProviders.getIfAvailable()).thenReturn(evalPort);
        when(studyPort.studySignal(USER, DIR, FROM, TO))
            .thenReturn(new StudySignal(Duration.ofMinutes(45), Duration.ZERO));
        List<SessionOutcome> outcomes = List.of(
            outcome("s1", 90, Instant.parse("2026-09-20T00:00:00Z")));
        when(evalPort.recentOutcomes(USER, DIR, FROM, TO)).thenReturn(outcomes);

        SignalSnapshot a = facade().readDirectional(USER.toString(), DIR, FROM, TO);
        SignalSnapshot b = facade().readDirectional(USER.toString(), DIR, FROM, TO);

        assertThat(a).isEqualTo(b);
    }

    private static SessionOutcome outcome(String id, Integer score, Instant finishedAt) {
        return new SessionOutcome(id, DIR.toString(), score, finishedAt,
            "chat-m", "eval-m", "hash", "v2");
    }
}
