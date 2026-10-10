-- V21__report_session_polymorphic.sql — P3-05：报告与逐题评估的 session_id 多态化
-- 依据：docs/specs/2026-10-06-voice-adr.md 修订 1 §2。VOICE 报告的 session_id 宿主是
--       voice_session（V18），同一列不能有两个父表，故解除对 interview_session 的外键。
-- 代价（ADR 已记账，不是遗漏）：两表失去 session_id 级联；interview_report.user_id 的外键
--       与级联保留，账号删除仍能清掉报告，逐题明细的清理归注销/导出批（TD）。
-- 约束：既有迁移冻结禁改；本次不加列，只解除约束并改注释——多态语义由 session_type（V20）承载。

ALTER TABLE interview_report DROP CONSTRAINT interview_report_session_id_fkey;
ALTER TABLE interview_evaluation DROP CONSTRAINT interview_evaluation_session_id_fkey;

COMMENT ON COLUMN interview_report.session_id IS '会话宿主 id：session_type=INTERVIEW 时是 interview_session.id，=VOICE 时是 voice_session.id（V20/V21 多态化，无外键背书——写入方必须按类型取自对应会话）';
COMMENT ON COLUMN interview_evaluation.session_id IS '所属会话 id，与 interview_report.session_id 同宿主同语义（V21 多态化）；跨类型读路径见 voice-adr 修订 1 §2 代价 2';
