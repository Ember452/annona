# ADR: 不内置任何模型 API Key；Key 按用途五分且生产缺 KEK 即启动失败

- 日期：2026-09-25
- 状态：Accepted
- 相关：[../annona-项目设计文档.md](../annona-项目设计文档.md) §12.1

## 背景

annona 同时服务两类部署：自部署（用户自带 Key）与官方托管（平台代持 Key）。上游 interview-guide 的 `ApiKeyEncryptionService` 里有 `DEV_FALLBACK_KEY = "interview-guide-dev-only-provider-api-key-encryption"`——未配置主密钥时静默使用该常量加密。

## 决策

1. **项目不内置任何可用 Key**：代码、配置、镜像、seed 数据、测试夹具中均不得出现真实 Key；`.env.example` 只给占位符。
2. **KEK 与密文分离**：加密主密钥来自环境变量 `ANNONA_SECRET_KEY`（可接 KMS/Vault），`model_key` 表只存 `nonce` + `ciphertext` + `kek_version`。
3. **生产 profile 缺 KEK → `StartupValidator` 抛异常拒绝启动**。**不采用上游的静默 fallback**；dev 需要 fallback 时只在 `application-dev.yaml` 显式声明，该 profile 不进生产镜像。
4. **Key 按五种用途独立配置**：`CHAT / EMBEDDING / ASR / TTS / EVALUATOR`（`model_key.usage`）。一个 Provider 的一个 Key 可登记到多个 usage（DashScope 场景），也可完全异源（DeepSeek 出题 + LM Studio 向量化 + 讯飞 TTS）。
5. 明文 Key 永不下发前端，响应只返回 `maskedApiKey`；日志与异常栈必须脱敏；解密结果不进任何缓存。
6. 托管代持模式必须晚于 `token_usage` 记账 + 每日配额 + 超额熔断上线。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 内置一个演示 Key（"开箱即用"体验更好） | 开源项目里等同于公开 Key，会被爬虫耗尽并产生账单事故；且与 BYOK 的产品主张自相矛盾 |
| 沿用 `DEV_FALLBACK_KEY` 静默兜底 | 「忘配环境变量」也能启动成功，日后补配真 KEK 时存量密文全部解不开，是一次必然发生的数据事故 |
| 明文存 Key 到 `.env`，不落库 | 无法支持多 Provider、运行时切换与用户级隔离，UI 配置能力做不了 |
| Key 只按 Provider 存一份（不区分用途） | 语音（TTS/P3）接入时必须改表；且现实中向量化模型与对话模型常常异源异供应商 |

## 后果与约束

1. `model_key.usage` 与 `kek_version` 字段必须在 P0 建表时落定，否则 P3 语音接入涉及数据迁移。
2. CI 增加密钥扫描（gitleaks，pre-commit + workflow 双道），防止贡献者误提交。
3. 本地开发首次启动必须显式设置一个开发用 KEK，README 的 5 分钟跑通脚本要包含这一步（不能靠 fallback 悄悄成功）。
4. KEK 轮换需要提供 `annona reencrypt --kek-version` 命令，否则 `kek_version` 字段是死字段。
