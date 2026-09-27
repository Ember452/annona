-- V2__study_collection.sql — P1a-04 学习行为采集
-- 覆盖：checkin（每日打卡，先建）+ study_session（专注会话）+ study_event（会话事件）
-- 依据：docs/annona-项目设计文档.md §4.A/§5.2/§6.1、docs/specs/2026-09-26-study-collection-adr.md
-- 约束：与 V1 相同——PG 方言（TIMESTAMPTZ / JSONB）、snake_case、不加反引号、
--       业务表含 user_id 且 (user_id, …) 复合索引；外键列必有索引（例外见行内注释）

-- ========== 1. checkin：每日打卡（先建：study_session.checkin_id 引用它） ==========
CREATE TABLE checkin (
    id           UUID          PRIMARY KEY,
    user_id      UUID          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    direction_id UUID          NOT NULL REFERENCES direction (id),
    day          DATE          NOT NULL,
    hours        NUMERIC(4,1)  NOT NULL DEFAULT 0,
    mood         VARCHAR(32)   NULL,
    energy       INT           NULL,
    note         VARCHAR(500)  NULL,
    snapshot_url VARCHAR(255)  NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_checkin_energy CHECK (energy IS NULL OR energy BETWEEN 1 AND 5),
    CONSTRAINT chk_checkin_hours  CHECK (hours >= 0 AND hours <= 24),
    -- 一天一条：幂等 upsert 的依据（ADR §决策 4）；上游在 UI 层挡“今天已经来过了”，
    -- annona 把约束下沉到 DB——统计口径不被切碎
    CONSTRAINT uq_checkin_user_day UNIQUE (user_id, day)
);
COMMENT ON COLUMN checkin.direction_id IS '打卡必选方向（§5.1：科目 = 方向下拉 + 可即时新建）；direction 只归档不物理删，无级联扫描面，故不设独立索引';
COMMENT ON COLUMN checkin.snapshot_url IS '打卡截图（RustFS/S3 object key）；上传链路属后续阶段，先留列';
COMMENT ON COLUMN checkin.hours IS '当日自报学习时长（小时，0.5 步进精度）；>0 时同事务联动落 mode=CHECKIN / quality=SELF_REPORTED 的 study_session（ADR §决策 3）';

-- ========== 2. study_session：一次专注/学习会话（时长的单一真相源） ==========
CREATE TABLE study_session (
    id           UUID         PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    direction_id UUID         NOT NULL REFERENCES direction (id),
    checkin_id   UUID         NULL REFERENCES checkin (id) ON DELETE SET NULL,
    mode         VARCHAR(16)  NOT NULL,
    start_at     TIMESTAMPTZ  NOT NULL,
    end_at       TIMESTAMPTZ  NULL,
    minutes      INT          NULL,
    quality      VARCHAR(16)  NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_study_session_mode    CHECK (mode IN ('POMODORO', 'IMMERSIVE', 'CHECKIN')),
    CONSTRAINT chk_study_session_quality CHECK (quality IN ('VERIFIED', 'PARTIAL', 'SELF_REPORTED'))
);
COMMENT ON COLUMN study_session.checkin_id IS '打卡 hours 联动的会话（ADR §决策 3）；更新打卡按本列定位，避免按 (day, direction) 反查的脆匹配';
COMMENT ON COLUMN study_session.end_at IS 'NULL = 进行中（不设 status 列）；未 finish 的会话原样展示，不写定时任务收尾（ADR §后果）';
COMMENT ON COLUMN study_session.minutes IS 'finish 时由服务端按心跳时间线计算（QualityGrader），前端上报一律不采信；进行中为 NULL';
COMMENT ON COLUMN study_session.quality IS 'VERIFIED（心跳覆盖时长）/ PARTIAL（maxGap≥30min，按墙钟补上）/ SELF_REPORTED（手动补录或打卡）——判定算法唯一权威定义在 study-collection-adr §决策 2';
COMMENT ON COLUMN study_session.mode IS 'POMODORO | IMMERSIVE | CHECKIN；IMMERSIVE 是设计 §4.A 的枚举位，生产路径 P2 才启用';
CREATE INDEX idx_study_session_user_start ON study_session (user_id, start_at DESC);
-- P1c-01 信号聚合（按 direction_id 聚合、方向相交才参与）直接命中本索引
CREATE INDEX idx_study_session_user_dir_start ON study_session (user_id, direction_id, start_at);

-- ========== 3. study_event：会话内原子事件（心跳不入此表，ADR §决策 1） ==========
CREATE TABLE study_event (
    id         UUID         PRIMARY KEY,
    session_id UUID         NOT NULL REFERENCES study_session (id) ON DELETE CASCADE,
    type       VARCHAR(16)  NOT NULL,
    at         TIMESTAMPTZ  NOT NULL,
    payload    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT chk_study_event_type CHECK (type IN ('START', 'BLUR', 'FINISH', 'INTERRUPT'))
);
COMMENT ON COLUMN study_event.type IS 'START/BLUR 由会话创建与前端失焦上报；FINISH/INTERRUPT 由 finish(abandon) 服务端落——HEARTBEAT 刻意不在枚举内（心跳是活着的证明，只服务质量判定，ADR §背景）';
COMMENT ON TABLE study_event IS '会话作用域子表，不冗余 user_id（破结构文档「业务表必含 user_id」约定，评审 C2）：只能通过 session_id 访问、user 经 study_session 外键可达且 ON DELETE CASCADE 同级联清理，当前无任何按 user 直查事件的读路径';
CREATE INDEX idx_study_event_session ON study_event (session_id, at);
