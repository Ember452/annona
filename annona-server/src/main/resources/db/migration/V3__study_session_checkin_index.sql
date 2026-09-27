-- V3__study_session_checkin_index.sql — P1a-04 加固：补 study_session.checkin_id 定位索引
-- 依据：docs/specs/2026-09-27-study-checkin-index-adr.md
-- 冻结提醒：V1/V2 已被真实环境应用（2026-09-26 CI run #14 起），禁改——
-- 本文件即"新结构走新版本号"约定的第一个产物（P1a-05 知识库表从 V4 开始）。

-- checkin_id 是打卡联动的定位列（V2 列注释明写"更新打卡按本列定位"）：
-- CheckinService.syncLinkedSession 在每次 hours>0 的打卡 upsert 里 findByCheckinId，
-- 且 checkin 的 ON DELETE SET NULL 级联要靠它定位子行。V2 只建了 (user_id, start_at)
-- 与 (user_id, direction_id, start_at)，漏了这条——违反 V2 头部"外键列必有索引"的
-- 自定约束。数据量小时 seq scan 无感知；P1c 把本表当时长真相源后按天增长，
-- 打卡接口延迟会随之线性劣化，故随加固批提前补上。
CREATE INDEX idx_study_session_checkin ON study_session (checkin_id);
