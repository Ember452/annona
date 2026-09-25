# ADR: 身份与会话存储方案（scrypt + Redis 会话 + 单机模式同表 + 两段式删除）

- 日期：2026-09-25
- 状态：Accepted
- 相关：[../annona-项目设计文档.md](../annona-项目设计文档.md) §5.3、[2026-09-25-storage-single-postgres-adr.md](./2026-09-25-storage-single-postgres-adr.md)、[2026-09-25-model-api-key-adr.md](./2026-09-25-model-api-key-adr.md)

## 背景

annona 要同时支持三种运行形态：单机免登录（自部署个人用）、多人自部署（一个宿舍/一个自习室共用）、官方托管（开放注册）。同时项目定位为开源 + AGPL，用户会把实例部署在自己的 VPS 上，环境差异（无 SMTP、Windows、JDK 发行版各异）必须被考虑进来。

原设计文档 §5.2 身份相关只有一行 `app_user`，注册、会话、凭据、删除的存放位置均未定义。

## 决策

1. **身份数据全部落 PostgreSQL 单库**，新增 7 张表：`app_user`、`user_profile`、`user_session`、`auth_token`、`login_attempt`、`avatar_change`、`user_data_request`。
2. **口令哈希用 scrypt**（`spring-security-crypto` 纯 Java 实现，N=2^15/r=8/p=1，每用户随机 salt），参数写进 `app_user.password_algo` 以便日后升级。
3. **活跃会话只在 Redis**（HttpOnly Cookie `ANNONA_SESSION` → `session:{token}`，7 天滑动 TTL）；PG 的 `user_session` 只是异步写入的审计投影。
4. **单机免登录模式不另立数据模型**：`annona.identity.mode=none` 时启动 bootstrap 一个固定用户，所有业务表照常带 `user_id`。
5. **删除两段式**：软删（`status=DELETED`，可见性立即归零）+ 30 天宽限后物理删，由 `user_data_request` 承载。
6. **一次性凭据只存哈希**（`auth_token.token_hash`）；v1 不做邮箱强制验证、不做第三方 OAuth；不采集姓名/手机号/学校等真实身份信息。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| Argon2id 做口令哈希 | 算法本身更优，但需要 native 依赖（Bouncy Castle 之外的 JNI 实现），Windows 与部分 JDK 发行版上编译/运行失败率高；自部署产品的第一号杀手是"clone 下来跑不起来"，而口令哈希在 scrypt 这个强度下差异不构成实际风险 |
| JWT 无状态会话 | 无法即时吊销（改密码、踢下线、超额熔断都要等 token 自然过期）；且 annona 本来就必须有 Redis（限流、Stream、在线状态），引入 JWT 只会多一套密钥轮换与吊销黑名单，等于把状态又搬回 Redis |
| 会话直接存 PG（不用 Redis） | 每次请求一次写库，打卡心跳与 SSE 场景下写放大明显；Redis 已是必选依赖，没有理由再加库压力 |
| 单机模式去掉 `user_id` 字段（简单点） | 短期省一个字段，代价是单机用户升级到多人时要给全部历史数据回填归属，而学习记录是决策层的输入，一旦归属错乱则掌握度与热力图全部失真；这不是能后补的改动 |
| 硬删除（GDPR 式立即清除） | 学习数据是用户数月积累，误删不可挽回；且决策层的样本量会因历史清空而倒退。30 天宽限同时满足"可撤回"与"最终真删" |
| 接入 GitHub OAuth（开源项目标配） | 自部署用户填 Key 就完事，不需要身份联合；托管版需要时再加，`auth_token.purpose` 已预留扩展位，不会因此改表 |

## 后果与约束

1. **Redis 是必选依赖**，不是性能优化项：没有它登录态无处存放。`docker-compose` 与"最小可跑集"里 Redis 与 PG 同级，文档不能写"Redis 可选"。
2. `user_session` 异步写失败必须静默降级（只记 warn 日志），绝不能让审计投影写入影响用户登录。
3. 口令参数升级路径必须可用：`password_algo` + `password_hash` 前缀编码参数，登录成功时透明重哈希（scrypt 参数变大或换算法时不需用户改密码）。
4. 邮箱唯一性依赖 PG `citext` 扩展，`V1__baseline.sql` 需 `CREATE EXTENSION IF NOT EXISTS citext`（与 pgvector 一同声明，否则部分托管环境会缺）。
5. 注册接口必须挂 `@RateLimit`（IP + email 双维度），配合 `login_attempt` 锁定，否则开源项目上线即被脚本注册刷。
6. 数据导出（`annona export --user`）必须覆盖 7 张身份表 + 全部业务表 + S3 原始文件，产物是单个 zip 的 object key。

## 何时重新评估

- 出现真实的多身份源需求（学校统一认证、企业 SSO）→ 引入 `IdentityProvider` 的 OAuth/SAML 实现，而非在 `app_user` 上加分支字段；
- 若合规要求明确到必须 Argon2 或 FIPS 认证算法 → 借 `password_algo` 的透明重哈希路径切换，不需要停机迁移。
