-- V10__llm_provider.sql — P1b-10：用户级 Provider 配置（宽口径）
-- 依据：docs/specs/2026-09-29-llmprovider-metering-adr.md（宽口径 = rerank/tts/asr 提前建设，
-- 是计划外加码并已在 ADR 记录取舍）。密文三列结构原样沿用 2026-09-25-model-api-key-adr §决策 2：
-- nonce + ciphertext + kek_version——kek_version 是 KEK 轮换（该 ADR 后果条款 4 的 reencrypt
-- 命令）的支点，单列密文方案在其否决表内，本表不重开。
-- 表名偏离 ADR 里的 model_key 草记：这张表存的是"provider + 用途 + 端点 + Key"的配置行，
-- 不是纯密钥表；KEK ADR 的实质裁决（三列结构与用途五分）完整保留，metering ADR 标注扩展关系。

CREATE TABLE llm_provider_config (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    provider_key   VARCHAR(64) NOT NULL,
    base_url       VARCHAR(512) NULL,
    purpose        VARCHAR(16) NOT NULL,
    api_key_nonce  BYTEA NOT NULL,
    api_key_cipher BYTEA NOT NULL,
    kek_version    VARCHAR(32) NOT NULL,
    api_key_masked TEXT NOT NULL,
    default_model  VARCHAR(128) NULL,
    enabled        BOOLEAN NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_provider_purpose CHECK (purpose IN
        ('chat', 'embedding', 'rerank', 'tts', 'asr', 'evaluator')),
    CONSTRAINT uq_provider_user_key_purpose UNIQUE (user_id, provider_key, purpose)
);
COMMENT ON TABLE llm_provider_config IS '用户自带 Key（BYOK）的 provider 配置；uq(user,provider,purpose)——同 provider 多用途各一行，DashScope 一 Key 多用途与异源异供应商都表达得了（KEK ADR §决策 4）';
COMMENT ON COLUMN llm_provider_config.id IS '应用侧 UUID.randomUUID()，无 @GeneratedValue（全仓约定）';
COMMENT ON COLUMN llm_provider_config.provider_key IS '供应商标识（openai/dashscope/deepseek/…），与 env 全局配置的 key 同域';
COMMENT ON COLUMN llm_provider_config.base_url IS 'OpenAI 兼容端点；NULL 走该 provider 的内置默认地址';
COMMENT ON COLUMN llm_provider_config.purpose IS '六用途 CHECK（chat/embedding/rerank/tts/asr/evaluator）；evaluator 为批 3 评分 Key 异源预留（现在不加 = 将来 V12，KEK ADR 否决表同款事故）';
COMMENT ON COLUMN llm_provider_config.api_key_nonce IS 'AES/GCM 96-bit nonce，每记录独立（KEK ADR §决策 2）';
COMMENT ON COLUMN llm_provider_config.api_key_cipher IS 'AES/GCM 密文；明文永不下发前端、不进日志与缓存（KEK ADR §决策 5）';
COMMENT ON COLUMN llm_provider_config.kek_version IS '加密时的 ANNONA_SECRET_KEY 版本；轮换走 annona reencrypt（P 后期命令）';
COMMENT ON COLUMN llm_provider_config.api_key_masked IS '入库时由明文生成的掩码（首4尾2），对外只回本列';
COMMENT ON COLUMN llm_provider_config.enabled IS '软开关；禁用行保留密文（Key 未轮换前不销毁）';
CREATE INDEX idx_provider_config_user ON llm_provider_config (user_id, enabled);
