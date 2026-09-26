package io.annona.modules.study.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 打卡响应（GET /checkins/today 为 null 表示今天还没打过）。
 */
public record CheckinResponse(
    String id,
    String directionId,
    LocalDate day,
    BigDecimal hours,
    String mood,
    Integer energy,
    String note,
    String snapshotUrl,
    Instant createdAt
) {
}
