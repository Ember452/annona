-- V15__decision_trace.sql — P1c-05：决策留痕（可解释面板的数据源）
-- 依据：docs/specs/2026-09-30-planner-decision-kernel-adr.md（trace 落库时机：随会话事务写入）。
-- 一条 = 一次组卷中某条规则/guard 的命中或保护留痕；可解释面板据此回答"凭什么这么考我"。
-- 形状对齐 io.annona.spi.dto.DecisionTrace，另加会话/方向/用户归属与输入快照（复现"当时的决策依据"）。

CREATE TABLE decision_trace (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES interview_session(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    direction_id    UUID NOT NULL REFERENCES direction(id),
    rule_key        VARCHAR(32) NOT NULL,
    action          VARCHAR(48) NOT NULL,
    reason          TEXT NOT NULL,
    rejected_by     VARCHAR(32) NULL,
    rejection_count INT NOT NULL DEFAULT 0,
    input_snapshot  JSONB NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_trace_rejection_count CHECK (rejection_count >= 0)
);
COMMENT ON TABLE decision_trace IS '决策留痕（P1c-05）：组卷时规则链/guard 的每条命中或保护都落一行；可解释面板与 A/B 的证据源';
COMMENT ON COLUMN decision_trace.id IS '应用侧 UUID.randomUUID()，无 @GeneratedValue（全仓约定）';
COMMENT ON COLUMN decision_trace.rule_key IS '规则标识：FORGETTING_CURVE/WEAK_DIRECTION/SAMPLE_GUARD/VERSION_BASELINE/SELF_REPORTED/NO_STUDY_RECORD/REMIND_REVIEW';
COMMENT ON COLUMN decision_trace.action IS '本规则施加的动作（如 RAISE_DIFFICULTY/LOWER_DIFFICULTY_REVIEW/NO_ADJUST/BASELINE_ONLY），供面板分组';
COMMENT ON COLUMN decision_trace.reason IS '面向用户的原因文案（人话，禁止为空）；面板直接展示';
COMMENT ON COLUMN decision_trace.rejected_by IS '被下游保护规则否决时记否决者 rule_key；未被否决为 NULL（P1c-07 反驳亦写本列为 USER）';
COMMENT ON COLUMN decision_trace.rejection_count IS '该条留痕被用户驳回的次数（P1c-07）；达阈值联动 rule_reputation 降权';
COMMENT ON COLUMN decision_trace.input_snapshot IS '决策时的信号快照 JSON（SignalSnapshot 关键面），支持"这条决策依据哪些数据"的可核对复现；可空（降级路径不留快照）';
COMMENT ON COLUMN decision_trace.created_at IS 'DB DEFAULT now()，insertable=false';
-- 面板"最近 5 场"读路径：按用户时间倒序
CREATE INDEX idx_trace_user_created ON decision_trace (user_id, created_at DESC);
-- 单场全部留痕（报告页决策理由节）
CREATE INDEX idx_trace_session ON decision_trace (session_id);
