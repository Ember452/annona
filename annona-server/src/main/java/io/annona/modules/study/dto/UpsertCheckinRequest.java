package io.annona.modules.study.dto;

import java.math.BigDecimal;

/**
 * POST /api/study/checkins 请求体：打卡（一天一条，重复提交即更新）。
 *
 * @param directionId 方向 id（必选，§5.1）
 * @param hours       当日自报学习时长（0..24 小时，0.5 步进精度）；>0 时联动落会话
 * @param mood        心情，≤32 字符
 * @param energy      自评能量值 1..5，可空
 * @param note        备注，≤500 字符
 * @param snapshotUrl 打卡截图 object key，≤255 字符（上传链路属后续阶段）
 */
public record UpsertCheckinRequest(
    String directionId,
    BigDecimal hours,
    String mood,
    Integer energy,
    String note,
    String snapshotUrl
) {
}
