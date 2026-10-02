package io.annona.common.support;

import java.time.ZoneId;

/**
 * 应用内"日界"时区的唯一出处。
 *
 * <p><b>选什么</b>：常量 {@link #DAILY}（Asia/Shanghai）。打卡日、学习会话的"今日"、
 * 决策参考日与 demo 数据合成都按它切日——同一个人在同一天里的这些事实必须落在同一个
 * 日子上，否则"近 14 天专注占比"这类口径互相对不上账。
 *
 * <p><b>否决什么</b>：① 各模块自持 {@code ZoneId.of("Asia/Shanghai")}——曾散落 3 处，
 * 改一处漏一处，且决策链另外两处一个用 UTC 一个跟 JVM 默认区（同一次决策三种口径）；
 * ② 跟随 JVM 默认时区——容器普遍是 UTC，日界整体偏 8 小时，衰减天数与样本窗口一起错；
 * ③ 做成配置键——这个值是产品口径而不是部署差异，配出去只会制造环境间不一致。
 *
 * <p><b>何时重新评估</b>：{@code user_profile.timezone} 开始真正生效（per-user 日界）时，
 * 本常量退为"系统默认"，日界改走用户时区；那要求打卡、统计与决策三条链一起改
 * （见 study-collection-adr §后果）。
 */
public final class AppZones {

    /** 日界时区：打卡日、"今日"与决策参考日共用。 */
    public static final ZoneId DAILY = ZoneId.of("Asia/Shanghai");

    private AppZones() {
    }
}
