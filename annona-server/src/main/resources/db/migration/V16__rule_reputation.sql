-- V16__rule_reputation.sql — P1c-07：规则声誉（反驳降权与自动停用）
-- 依据：docs/specs/2026-09-30-planner-decision-kernel-adr.md（拒绝内建停用逻辑到规则里，
-- 降权状态是数据不是代码——规则链据本表 disabled 过滤，无需重启即生效）。
-- 一条 = 一个 (user, rule_key) 的声誉：累计驳回次数达阈值自动 disabled。

CREATE TABLE rule_reputation (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    rule_key       VARCHAR(32) NOT NULL,
    rejected_count INT NOT NULL DEFAULT 0,
    disabled       BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reputation_user_rule UNIQUE (user_id, rule_key),
    CONSTRAINT chk_reputation_count CHECK (rejected_count >= 0)
);
COMMENT ON TABLE rule_reputation IS '规则声誉（P1c-07）：用户驳回某规则累计达阈值 → disabled，规则链跳过该规则并留痕';
COMMENT ON COLUMN rule_reputation.id IS '应用侧 UUID.randomUUID()，无 @GeneratedValue（全仓约定）';
COMMENT ON COLUMN rule_reputation.rule_key IS '被计数的规则标识（对齐 decision_trace.rule_key）';
COMMENT ON COLUMN rule_reputation.rejected_count IS '累计驳回次数；达配置阈值（默认 3）时置 disabled=true';
COMMENT ON COLUMN rule_reputation.disabled IS 'true=该规则对该用户停用；规则链过滤依据。停用可留痕恢复（人工），v1 不自动恢复';
COMMENT ON COLUMN rule_reputation.created_at IS 'DB DEFAULT now()，insertable=false';
COMMENT ON COLUMN rule_reputation.updated_at IS '驳回计数时应用侧推进';
-- 规则链装配读路径：按用户取全部声誉行（量级小，直接全取不过索引）
CREATE INDEX idx_reputation_user ON rule_reputation (user_id);
