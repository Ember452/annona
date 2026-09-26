-- V1__baseline.sql — annona 数据基线
-- 覆盖：PG 扩展 + 身份 7 表（app_user / user_profile / user_session / auth_token /
--       login_attempt / avatar_change / user_data_request）+ direction 主数据表
-- 依据：docs/annona-项目设计文档.md §5.3、docs/specs/2026-09-25-identity-credential-storage-adr.md、
--       docs/specs/2026-09-25-direction-master-data-adr.md、docs/specs/2026-09-25-storage-single-postgres-adr.md
-- 约束：PG 方言（TIMESTAMPTZ / CITEXT / JSONB / 部分索引）；不加反引号；snake_case；
--       业务表含 user_id 且 (user_id, …) 复合索引
-- 修订规则：本文件已在全新库上跑过；**一旦任何环境（含用户自部署）执行过 V1，
-- 禁止再改 V1，一律新增 V<n>__xxx.sql**（当前仅 CI 临时库，故仍可原地改）。

-- ========== 扩展（StartupValidator / FlywayExtensionGuard 会 pre-check） ==========
-- 包 DO 块并吞 insufficient_privilege：云托管 PG 常给应用账号一个受限角色，
-- 裸 CREATE EXTENSION 会让整个迁移失败；此时应改由超户或控制台预装，而不是炸启动。
DO $$
BEGIN
    EXECUTE 'CREATE EXTENSION IF NOT EXISTS vector';
EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'skipped CREATE EXTENSION vector (insufficient privilege); install it as superuser or via the provider console';
END
$$;
DO $$
BEGIN
    EXECUTE 'CREATE EXTENSION IF NOT EXISTS citext';
EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'skipped CREATE EXTENSION citext (insufficient privilege); install it as superuser or via the provider console';
END
$$;

-- ========== 1. app_user：身份主体 ==========
CREATE TABLE app_user (
    id             UUID         PRIMARY KEY,
    email          CITEXT       NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    password_algo  VARCHAR(32)  NOT NULL DEFAULT 'scrypt',
    status         VARCHAR(32)  NOT NULL,
    role           VARCHAR(16)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ  NULL,
    CONSTRAINT chk_app_user_status CHECK (status IN ('ACTIVE', 'PENDING_VERIFY', 'LOCKED', 'DELETED')),
    CONSTRAINT chk_app_user_role   CHECK (role IN ('USER', 'ADMIN'))
);
COMMENT ON COLUMN app_user.status IS 'ACTIVE | PENDING_VERIFY | LOCKED | DELETED';
COMMENT ON COLUMN app_user.role   IS 'USER | ADMIN';
COMMENT ON COLUMN app_user.password_algo IS '口令哈希算法+参数标识；升级算法时靠它做登录时透明重哈希（identity ADR §后果 §3）';
-- 邮箱唯一只对**活跃用户**成立：上一版的全局 UNIQUE 会让“软删后 30 天宽限期内
-- 用同一邮箱重新注册”直接撞约束，与两段式删除的设计自相矛盾。
CREATE UNIQUE INDEX uq_app_user_email ON app_user (email) WHERE deleted_at IS NULL;
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
    PRIMARY KEY (purpose, token_hash),
    CONSTRAINT chk_auth_token_purpose CHECK (purpose IN ('EMAIL_VERIFY', 'PASSWORD_RESET', 'SECOND_FACTOR'))
);
COMMENT ON COLUMN auth_token.purpose IS 'EMAIL_VERIFY | PASSWORD_RESET | SECOND_FACTOR（预留 OAuth 扩展位，见 identity ADR §决策 §6）';
COMMENT ON COLUMN auth_token.token_hash IS '只存哈希；主键以 purpose 打头，按 token 反查走下面的 (token_hash) 索引';
-- 上一版 PK 是 (user_id, purpose, token_hash)，前导列是 user_id → 改密/验邮这类
-- “拿到 token 反查用户”的查询完全用不上索引，等于全表扫 + 可被时间差探测。
CREATE INDEX idx_auth_token_hash   ON auth_token (token_hash);
CREATE INDEX idx_auth_token_user   ON auth_token (user_id);
CREATE INDEX idx_auth_token_expiry ON auth_token (expires_at);

-- ========== 5. login_attempt：失败锁定与风控 ==========
CREATE TABLE login_attempt (
    -- 320 = email 上限 254（RFC 5321，入口校验见 Emails）+ 分隔符 1 + IPv6 地址 45；
    -- 上一版 255 会让超长邮箱在登录失败路径撞列长、伪装成 500（加固批，见 identity ADR 后续修订）
    key          VARCHAR(320) PRIMARY KEY,
    fail_count   INT          NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ  NULL,
    last_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON COLUMN login_attempt.key IS 'email + ip 复合键，用于枚举防护与暴力破解锁定（identity ADR §后果 §5）';
COMMENT ON TABLE login_attempt IS '预认证表：登录失败时还不知道是谁，所以没 user_id；它是“业务表必含 user_id”规则的已记录例外';
-- 清理作业靠它按时间范围扫描，否则只能全表扫
CREATE INDEX idx_login_attempt_last_at ON login_attempt (last_at);

-- ========== 6. avatar_change：头像历史，支持一键回滚 ==========
CREATE TABLE avatar_change (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    object_key VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON COLUMN avatar_change.id IS '上一版用 (user_id, object_key) 做主键：回滚到曾用头像会插入同一 object_key 而撞主键';
CREATE INDEX idx_avatar_change_user ON avatar_change (user_id, created_at DESC);

-- ========== 7. user_data_request：数据导出与两段式硬删除 ==========
CREATE TABLE user_data_request (
    id                 UUID        PRIMARY KEY,
    user_id            UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type               VARCHAR(16) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    file_object_key    VARCHAR(255),
    requested_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    scheduled_purge_at TIMESTAMPTZ NULL,
    done_at            TIMESTAMPTZ NULL,
    CONSTRAINT chk_user_data_request_type   CHECK (type IN ('EXPORT', 'DELETE')),
    CONSTRAINT chk_user_data_request_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED'))
);
COMMENT ON COLUMN user_data_request.type IS 'EXPORT | DELETE';
COMMENT ON COLUMN user_data_request.status IS 'PENDING | RUNNING | DONE | FAILED';
COMMENT ON COLUMN user_data_request.scheduled_purge_at IS '软删后 30 天宽限期终点；到时物理删（identity ADR §决策 §5）';
-- 外键列必须有索引：否则“查我的请求”全表扫，且父表级联删除时会逐行全扫本表
CREATE INDEX idx_user_data_request_user ON user_data_request (user_id);
CREATE INDEX idx_user_data_request_due ON user_data_request (scheduled_purge_at)
    WHERE type = 'DELETE' AND done_at IS NULL;

-- ========== 8. direction：跨模块主数据（§5.1 + direction-master-data-adr） ==========
CREATE TABLE direction (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    key        VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    parent_id  UUID         NULL REFERENCES direction (id),
    origin     VARCHAR(32)  NOT NULL,
    kb_doc_id  UUID         NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    user_id    UUID         NULL REFERENCES app_user (id) ON DELETE CASCADE,
    meta_json  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_direction_origin CHECK (origin IN ('SKILL_BUILTIN', 'KNOWLEDGE_BASE', 'USER_CUSTOM', 'JD_PARSED')),
    CONSTRAINT chk_direction_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    -- 一个用户一个命名空间：两个用户都可以有叫「刑法学」的自定义方向。
    -- NULLS NOT DISTINCT 让内置方向（user_id 为 NULL）之间仍保持 key 全局唯一。
    CONSTRAINT uq_direction_owner_key UNIQUE NULLS NOT DISTINCT (user_id, key)
);
COMMENT ON COLUMN direction.id   IS '业务表引用方向时一律外键到本列（上一版用 key 当全局主键，会造成多用户同名方向相冲）';
COMMENT ON COLUMN direction.key  IS '全小写-dashed（如 java-concurrency）；owner 内唯一，不是全局唯一';
COMMENT ON COLUMN direction.origin IS 'SKILL_BUILTIN | KNOWLEDGE_BASE | USER_CUSTOM | JD_PARSED';
COMMENT ON COLUMN direction.kb_doc_id IS 'origin=KNOWLEDGE_BASE 时指向 kb_doc.id；kb_doc 表在 P1a-05 建，届时补 FK：ALTER TABLE direction ADD CONSTRAINT fk_direction_kb_doc FOREIGN KEY (kb_doc_id) REFERENCES kb_doc(id)';
COMMENT ON COLUMN direction.status IS 'ACTIVE | ARCHIVED；有历史数据的方向只能归档不能物理删（direction ADR §后果 §3）';
COMMENT ON COLUMN direction.user_id IS 'USER_CUSTOM 归属用户；SKILL_BUILTIN 与 KNOWLEDGE_BASE 时可为 NULL；ON DELETE CASCADE 保证用户物理删除不残留孤立方向';
CREATE INDEX idx_direction_user   ON direction (user_id)   WHERE user_id IS NOT NULL;
CREATE INDEX idx_direction_parent ON direction (parent_id) WHERE parent_id IS NOT NULL;
