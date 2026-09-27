package io.annona.modules.study.quality;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.study.quality.QualityGrader.GradeResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * QualityGrader 纯函数表驱动单测。算法唯一权威定义：study-collection-adr §决策 2；
 * 每个用例对应一条验收语义（VERIFIED 覆盖口径 / PARTIAL 30min 阈值 / SELF_REPORTED 无心跳）。
 */
@DisplayName("QualityGrader：质量分级判定算法")
class QualityGraderTest {

    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");

    /** 从 from 起每 interval 一跳，共 count 跳。 */
    private static List<Instant> heartbeats(Instant from, int count, Duration interval) {
        return IntStream.rangeClosed(1, count)
            .mapToObj(i -> from.plus(interval.multipliedBy(i)))
            .toList();
    }

    @Nested
    @DisplayName("VERIFIED：有心跳支撑的连续时长")
    class Verified {

        @Test
        @DisplayName("25 分钟连续心跳（每 15s 一跳）→ 覆盖口径 minutes=25")
        void continuousHeartbeatsCoverFullWall() {
            GradeResult result = QualityGrader.grade(T0, T0.plusSeconds(1500),
                heartbeats(T0, 99, Duration.ofSeconds(15)));

            assertThat(result.quality()).isEqualTo("VERIFIED");
            assertThat(result.minutes()).isEqualTo(25);
        }

        @Test
        @DisplayName("≤60s 的抖动计入覆盖（浏览器后台节流吸收）")
        void jitterWithinToleranceIsCovered() {
            // 段：首 15s（补足）+ 中 45s + 尾 60s（补足 15s）= 75s → 四舍五入 1 分钟
            GradeResult result = QualityGrader.grade(T0, T0.plusSeconds(120),
                List.of(T0.plusSeconds(15), T0.plusSeconds(60)));

            assertThat(result.quality()).isEqualTo("VERIFIED");
            assertThat(result.minutes()).isEqualTo(1);
        }

        @Test
        @DisplayName("60s~30min 的中断段时长丢失不计，但质量仍 VERIFIED（失焦几分钟的正常形态）")
        void mediumGapLosesCoverageButStaysVerified() {
            // 跳点间 4min（>60s 丢、<30min 不降级）；覆盖仅首尾补足 30s → 1 分钟
            GradeResult result = QualityGrader.grade(T0, T0.plusSeconds(600),
                List.of(T0.plusSeconds(60), T0.plusSeconds(300), T0.plusSeconds(540)));

            assertThat(result.quality()).isEqualTo("VERIFIED");
            assertThat(result.minutes()).isEqualTo(1);
        }

        @Test
        @DisplayName("乱序心跳输入被排序处理")
        void unsortedHeartbeatsAreSorted() {
            GradeResult result = QualityGrader.grade(T0, T0.plusSeconds(600),
                List.of(T0.plusSeconds(300), T0.plusSeconds(60), T0.plusSeconds(540)));

            assertThat(result.quality()).isEqualTo("VERIFIED");
        }
    }

    @Nested
    @DisplayName("PARTIAL：心跳缺失后补上（切标签页、休眠唤醒）")
    class Partial {

        @Test
        @DisplayName("恰好 30min 边界（≥ 判 PARTIAL）→ minutes=墙钟")
        void exactlyThirtyMinutesIsPartial() {
            // 中段 [T0+30s, T0+30min30s] 恰好 30min
            GradeResult result = QualityGrader.grade(T0, T0.plusSeconds(1860),
                List.of(T0.plusSeconds(30), T0.plusSeconds(1830)));

            assertThat(result.quality()).isEqualTo("PARTIAL");
            assertThat(result.minutes()).isEqualTo(31);
        }

        @Test
        @DisplayName("挂机 40 分钟后恢复继续 → PARTIAL 且 minutes=墙钟（验收条场景）")
        void idleGapThenResume() {
            List<Instant> jumps = new ArrayList<>();
            jumps.addAll(heartbeats(T0, 39, Duration.ofSeconds(15)));                 // T0+15s .. +9min45s
            jumps.addAll(heartbeats(T0.plusSeconds(3000), 19, Duration.ofSeconds(15))); // +50min15s .. +54min45s

            GradeResult result = QualityGrader.grade(T0, T0.plusSeconds(3300), jumps);

            assertThat(result.quality()).isEqualTo("PARTIAL");
            assertThat(result.minutes()).isEqualTo(55);
        }

        @Test
        @DisplayName("start 后即挂机（首段 35min 无心跳）→ PARTIAL")
        void idleFromStartIsPartial() {
            GradeResult result = QualityGrader.grade(T0, T0.plusSeconds(2160),
                List.of(T0.plusSeconds(2100)));

            assertThat(result.quality()).isEqualTo("PARTIAL");
            assertThat(result.minutes()).isEqualTo(36);
        }
    }

    @Nested
    @DisplayName("SELF_REPORTED：无心跳（finish 直连的防御路径）")
    class SelfReported {

        @Test
        @DisplayName("空心跳 → minutes=墙钟、quality=SELF_REPORTED")
        void noHeartbeatsIsSelfReported() {
            GradeResult result = QualityGrader.grade(T0, T0.plusSeconds(1500), List.of());

            assertThat(result.quality()).isEqualTo("SELF_REPORTED");
            assertThat(result.minutes()).isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("边界：倒置范围与时钟偏移")
    class EdgeCases {

        @Test
        @DisplayName("endAt 早于 startAt（异常输入）：墙钟夹为 0 → minutes=0")
        void negativeWallClockClampedToZero() {
            GradeResult result = QualityGrader.grade(T0.plusSeconds(600), T0, List.of());

            assertThat(result.quality()).isEqualTo("SELF_REPORTED");
            assertThat(result.minutes()).isZero();
        }

        @Test
        @DisplayName("心跳早于 startAt（客户端时钟快于服务）：负 gap 段被忽略，不污染判定")
        void heartbeatBeforeStartIgnored() {
            GradeResult result = QualityGrader.grade(T0, T0.plusSeconds(1500),
                List.of(T0.minusSeconds(600), T0.plusSeconds(15), T0.plusSeconds(30)));

            assertThat(result.quality()).isEqualTo("VERIFIED");
        }
    }
}
