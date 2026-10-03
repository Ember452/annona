package io.annona.modules.study.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 年度学习统计总览（P2-01，只读聚合）。
 *
 * @param year       统计年份
 * @param days       有会话记录的日聚合（服务端只回原始日聚合，不做全年补零——
 *                   连续天数/日均等派生口径由前端 lib/statsView 纯函数计算，便于测试与复用）
 * @param directions   方向维度聚合，已按有效时长降序
 * @param totalCheckins 累计打卡天数——注意是<b>全量</b>口径（跨年），供小岛解锁生长用；
 *                     days/directions 是年窗口内的数据，两套口径并存是有意的
 */
public record StatsOverviewResponse(int year, List<DayMinutes> days,
                                    List<DirectionMinutes> directions,
                                    long totalCheckins) {

    /**
     * 单日聚合。
     *
     * @param day                 日界按 AppZones.DAILY（Asia/Shanghai）归属
     * @param verifiedMinutes     VERIFIED+PARTIAL 分钟（与 planner 信号同口径的"有效专注"）
     * @param selfReportedMinutes SELF_REPORTED 分钟（含打卡联动会话，单独分列不与有效混算）
     */
    public record DayMinutes(LocalDate day, long verifiedMinutes, long selfReportedMinutes) {
    }

    /**
     * 单方向聚合。
     *
     * @param name 方向名；方向已归档（不再 visible）时显示占位名——历史时长仍须可见
     */
    public record DirectionMinutes(String directionId, String name,
                                   long verifiedMinutes, long selfReportedMinutes) {
    }
}
