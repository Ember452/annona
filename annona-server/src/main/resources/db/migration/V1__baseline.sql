-- V1__baseline.sql — annona 数据基线
-- 覆盖：PG 扩展 + 身份 7 表（app_user / user_profile / user_session / auth_token /
--       login_attempt / avatar_change / user_data_request）+ direction 主数据表
-- 依据：docs/annona-项目设计文档.md §5.3、docs/specs/2026-09-25-identity-credential-storage-adr.md、
--       docs/specs/2026-09-25-direction-master-data-adr.md、docs/specs/2026-09-25-storage-single-postgres-adr.md
-- 约束：PG 方言（TIMESTAMPTZ / CITEXT / JSONB / 部分索引）；不加反引号；snake_case；
--       业务表含 user_id 且 (user_id, …) 复合索引

-- ========== 扩展（StartupValidator 会 pre-check；缺时报可执行提示） ==========
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS citext;

-- ========== 1. app_user：身份主体 ==========
CREATE TABLE app_user (
    id             UUID         PRIMARY KEY,
    email          CITEXT       NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    password_algo  VARCHAR(32)  NOT NULL DEFAULT 'scrypt',
    status         VARCHAR(32)  NOT NULL,
    role           VARCHAR(16)  NOT NULL,
    onboard_cursor INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ  NULL
);
COMMENT ON COLUMN app_user.status IS 'ACTIVE | PENDING_VERIFY | LOCKED | DELETED';
COMMENT ON COLUMN app_user.role   IS 'USER | ADMIN';
-- 部分索引：仅覆盖活跃用户，减少删除后的索引膨胀（PG 特色）
CREATE INDEX idx_app_user_status ON app_user (status) WHERE deleted_at IS NULL;

-- ========== 2. user_profile：可编辑资料，1:1 与 app_user ==========
-- 与 app_user 拆表的理由见 identity-credential-storage-adr §决策 §1
CREATE TABLE user_profile (
    user_id           UUID         PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    nickname          VARCHAR(64),
    avatar_object_key VARCHAR(255),
    bio               VARCHAR(500),
    target_exam       VARCHAR(128),
    timezone          VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
    theme_key         VARCHAR(32)  NOT NULL DEFAULT 'rainforest',
    onboard_cursor    INT          NOT NULL DEFAULT 0
);

-- ========== 3. user_session：会话审计投影（非活跃令牌） ==========
-- 活跃令牌只存 Redis（7 天滑动 TTL）；本表异步写入，写失败不影响登录（identity ADR §后果 §2）
CREATE TABLE user_session (
    id           UUID         PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    device       VARCHAR(64),
    ip           VARCHAR(64),
    ua_hash      VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ  NULL
);
CREATE INDEX idx_user_session_user ON user_session (user_id, created_at DESC);

-- ========== 4. auth_token：一次性凭据（只存哈希） ==========
CREATE TABLE auth_token (
    user_id     UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    purpose     VARCHAR(32)  NOT NULL,
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ  NULL,
    ip          VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, purpose, token_hash)
);
COMMENT ON COLUMN auth_token.purpose IS 'EMAIL_VERIFY | PASSWORD_RESET | SECOND_FACTOR（预留 OAuth 扩展位，见 identity ADR §决策 §6）';
CREATE INDEX idx_auth_token_expiry ON auth_token (expires_at);

-- ========== 5. login_attempt：失败锁定与风控 ==========
CREATE TABLE login_attempt (
    key          VARCHAR(255) PRIMARY KEY,
    fail_count   INT          NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ  NULL,
    last_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON COLUMN login_attempt.key IS 'email + ip 复合键，用于枚举防护与暴力破解锁定（identity ADR §后果 §5）';

-- ========== 6. avatar_change：头像历史，支持一键回滚 ==========
CREATE TABLE avatar_change (
    user_id    UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    object_key VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, object_key)
);

-- ========== 7. user_data_request：数据导出与两段式硬删除 ==========
CREATE TABLE user_data_request (
    id                 UUID        PRIMARY KEY,
    user_id            UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type               VARCHAR(16) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    file_object_key    VARCHAR(255),
    requested_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    scheduled_purge_at TIMESTAMPTZ NULL,
    done_at            TIMESTAMPTZ NULL
);
COMMENT ON COLUMN user_data_request.type IS 'EXPORT | DELETE';
COMMENT ON COLUMN user_data_request.status IS 'PENDING | RUNNING | DONE | FAILED';
COMMENT ON COLUMN user_data_request.scheduled_purge_at IS '软删后 30 天宽限期终点；到时物理删（identity ADR §决策 §5）';
CREATE INDEX idx_user_data_request_due ON user_data_request (scheduled_purge_at)
    WHERE type = 'DELETE' AND done_at IS NULL;

-- ========== 8. direction：跨模块主数据（§5.1 + direction-master-data-adr） ==========
CREATE TABLE direction (
    key        VARCHAR(64)  PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    parent_key VARCHAR(64)  NULL REFERENCES direction (key),
    origin     VARCHAR(32)  NOT NULL,
    kb_doc_id  UUID         NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    user_id    UUID         NULL REFERENCES app_user (id),
    meta_json  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON COLUMN direction.key IS '全小写-dashed（如 java-concurrency）；所有业务表的方向字段一律外键到本列';
COMMENT ON COLUMN direction.origin IS 'SKILL_BUILTIN | KNOWLEDGE_BASE | USER_CUSTOM | JD_PARSED';
COMMENT ON COLUMN direction.kb_doc_id IS 'origin=KNOWLEDGE_BASE 时指向 kb_doc.id；kb_doc 表在 P1a-05 建，届时补 FK：ALTER TABLE direction ADD CONSTRAINT fk_direction_kb_doc FOREIGN KEY (kb_doc_id) REFERENCES kb_doc(id)';
COMMENT ON COLUMN direction.status IS 'ACTIVE | ARCHIVED；有历史数据的方向只能归档不能物理删（direction ADR §后果 §3）';
COMMENT ON COLUMN direction.user_id IS 'USER_CUSTOM 归属用户；SKILL_BUILTIN 与 KNOWLEDGE_BASE 时可为 NULL';
CREATE INDEX idx_direction_user   ON direction (user_id)   WHERE user_id IS NOT NULL;
CREATE INDEX idx_direction_parent ON direction (parent_key) WHERE parent_key IS NOT NULL;
