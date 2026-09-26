package io.annona.modules.study.quality;

import io.annona.modules.study.entity.StudySessionEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 质量分级纯函数（★服务端权威判定，无 Spring 无仓库依赖，可直接表驱动单测）。
 *
 * <p>算法唯一权威定义：docs/specs/2026-09-26-study-collection-adr.md §决策 2——
 * 把会话时间轴按心跳切分为 [start,t1] [t1,t2] … [tn,end] 各段：
 * <ul>
 *   <li>中间段 gap ≤ {@link #GAP_TOLERANT}（60s，吸收浏览器后台节流抖动）计入覆盖，否则该段丢失；</li>
 *   <li>首尾段最多补 {@link #HB_COVER}（15s，心跳证明自己前后一刻活着）；</li>
 *   <li>maxGap（含首尾段）≥ {@link #PARTIAL_THRESHOLD}（30min）→ PARTIAL（验收条：挂机 30 分钟无心跳 → PARTIAL）；</li>
 *   <li>无心跳 → SELF_REPORTED（finish 直连的防御路径，正常补录走 manual 端点）。</li>
 * </ul>
 * minutes 口径：VERIFIED = 覆盖时长（封顶墙钟）；PARTIAL = 墙钟（“心跳缺失后补上”）；
 * SELF_REPORTED = 墙钟。防欺诈靠 quality 标记 + P1c 决策侧消费，不靠扣时长。
 */
public final class QualityGrader {

    public static final Duration GAP_TOLERANT = Duration.ofSeconds(60);
    public static final Duration HB_COVER = Duration.ofSeconds(15);
    public static final Duration PARTIAL_THRESHOLD = Duration.ofMinutes(30);

    /** minutes 为整分钟数；quality 取值同 {@code StudySessionEntity.QUALITY_*}。 */
    public record GradeResult(int minutes, String quality) {
    }

    private QualityGrader() {
    }

    public static GradeResult grade(Instant startAt, Instant endAt, List<Instant> heartbeats) {
        Duration wall = Duration.between(startAt, endAt);
        if (wall.isNegative()) {
            wall = Duration.ZERO;
        }
        int wallMinutes = roundMinutes(wall);

        List<Instant> sorted = heartbeats.stream().distinct().sorted().toList();
        if (sorted.isEmpty()) {
            return new GradeResult(wallMinutes, StudySessionEntity.QUALITY_SELF_REPORTED);
        }

        List<Duration> gaps = new ArrayList<>();
        gaps.add(Duration.between(startAt, sorted.get(0)));
        for (int i = 1; i < sorted.size(); i++) {
            gaps.add(Duration.between(sorted.get(i - 1), sorted.get(i)));
        }
        gaps.add(Duration.between(sorted.get(sorted.size() - 1), endAt));

        Duration maxGap = gaps.stream()
            .filter(g -> !g.isNegative())
            .max(Comparator.naturalOrder())
            .orElse(Duration.ZERO);

        Duration covered = Duration.ZERO;
        for (int i = 0; i < gaps.size(); i++) {
            Duration gap = gaps.get(i);
            if (gap.isNegative()) {
                continue;
            }
            if (i == 0 || i == gaps.size() - 1) {
                covered = covered.plus(gap.compareTo(HB_COVER) > 0 ? HB_COVER : gap);
            } else if (gap.compareTo(GAP_TOLERANT) <= 0) {
                covered = covered.plus(gap);
            }
        }

        String quality = maxGap.compareTo(PARTIAL_THRESHOLD) >= 0
            ? StudySessionEntity.QUALITY_PARTIAL
            : StudySessionEntity.QUALITY_VERIFIED;
        int minutes = StudySessionEntity.QUALITY_PARTIAL.equals(quality)
            ? wallMinutes
            : Math.min(roundMinutes(covered), wallMinutes);
        return new GradeResult(minutes, quality);
    }

    private static int roundMinutes(Duration duration) {
        return (int) Math.round(duration.toMillis() / 60000.0);
    }
}
