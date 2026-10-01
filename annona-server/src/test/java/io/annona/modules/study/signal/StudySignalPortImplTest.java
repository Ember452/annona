package io.annona.modules.study.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.annona.modules.study.repository.StudySessionRepository;
import io.annona.shared.study.StudySignal;
import java.time.Duration;
import java.time.LocalDate;
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
@DisplayName("StudySignalPortImpl 质量分级聚合映射")
class StudySignalPortImplTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DIR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Mock
    private StudySessionRepository repository;

    @Test
    @DisplayName("有行：两列分别映射 VERIFIED+PARTIAL 与 SELF_REPORTED 分钟")
    void mapsBothColumns() {
        when(repository.aggregateQualityByDirection(eq(USER), eq(DIR), any(), any()))
            .thenReturn(List.<Object[]>of(new Object[]{100L, 20L}));

        StudySignal signal = new StudySignalPortImpl(repository).studySignal(USER, DIR, FROM, TO);

        assertThat(signal.verifiedMinutes()).isEqualTo(Duration.ofMinutes(100));
        assertThat(signal.selfReportedMinutes()).isEqualTo(Duration.ofMinutes(20));
    }

    @Test
    @DisplayName("SUM 返回 null（空集）：两列归零")
    void nullSumsBecomeZero() {
        when(repository.aggregateQualityByDirection(any(), any(), any(), any()))
            .thenReturn(List.<Object[]>of(new Object[]{null, null}));

        StudySignal signal = new StudySignalPortImpl(repository).studySignal(USER, DIR, FROM, TO);

        assertThat(signal.hasRecord()).isFalse();
    }

    @Test
    @DisplayName("仅自报：有效时长 0、自报 >0 → onlySelfReported=true")
    void onlySelfReportedDetected() {
        when(repository.aggregateQualityByDirection(any(), any(), any(), any()))
            .thenReturn(List.<Object[]>of(new Object[]{0L, 45L}));

        StudySignal signal = new StudySignalPortImpl(repository).studySignal(USER, DIR, FROM, TO);

        assertThat(signal.onlySelfReported()).isTrue();
    }
}
