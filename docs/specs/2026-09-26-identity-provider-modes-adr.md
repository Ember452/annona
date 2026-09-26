# ADR: 身份三模式（local / platform / none）的凭据来源与强制鉴权边界

- 日期：2026-09-26
- 状态：Accepted
- 相关：[../annona-开发计划.md](../annona-开发计划.md) P1a-02、[../annona-项目设计文档.md](../annona-项目设计文档.md) §5.3 / §13、[2026-09-25-dockerless-local-dev-adr.md](./2026-09-25-dockerless-local-dev-adr.md)、代码：`annona-spi/.../identity/IdentityProvider.java`、`annona-server/.../modules/identity/provider/`、`annona-server/.../config/web/SessionAuthFilter.java`

## 背景

P1a-01 落地了本地账号（注册/登录/登出/会话/锁定），但 `IdentityProvider` SPI **只有接口与内存 fake，没有任何生产实现**；`SessionAuthFilter` 也只解析 Cookie、不拒绝未认证请求，注释里写着"强制鉴权与 IdentityProvider 模式化在 P1a-02 引入"。于是出现两个必须现在定的事：

1. **`platform`（托管账号）与 `none`（单机免登录）到底靠什么识别身份？** 上游三个借鉴仓都没有对应实现（对 `X-Auth-Request|REMOTE_USER|X-Forwarded-User|trustedHeader|免登录` 全仓 grep 零命中），而设计文档 §5.3 的 7 张身份表里**没有**身份联合（link）表。
2. **未认证请求该由谁拒绝、返回什么形状？** 已有 `GlobalExceptionHandler` 作为唯一异常出口（HTTP 200 + `Result.error(code)`，前端拦截器按 `code` 分流），过滤器里手写 JSON 会与它漂移。

另有两处文档与代码不自洽：`IdentityProvider` 类注释说"实现由 `annona-infrastructure` 提供"（与结构文档 §4 的 `modules/identity/provider/` 矛盾，且 ArchUnit 禁止 modules→infra 反向依赖）；设计文档 §5.3 写"bootstrap 一个 `id=local` 用户"，而 `app_user.id` 是 UUID 主键，字面量 `local` 落不了库。本 ADR 一并定案。

## 决策

1. **三种模式由 `annona.identity.mode` 经 `@ConditionalOnProperty` 择一装配**，实现落在 `modules/identity/provider/`（不是 `annona-infrastructure`：provider 需要访问 identity 的仓储与 `AuthUserRegistrar`，放 infra 会造成 modules→infra 反向依赖，违反 ArchUnit 规则 1）。`local` 用 `matchIfMissing = true`，不配即默认本地账号。
2. **凭据来源按模式固定**：

   | 模式 | 凭据 | 校验 | 主体 |
   |---|---|---|---|
   | `local` | 会话 Cookie 令牌 | `SessionService.resolveAndSlide`（命中即滑动续期） | `Principal(userId, null, ∅)`；展示信息由消费方从库补齐 |
   | `platform` | 受信反代请求头（默认 `X-Auth-Request-Email`，可配 `annona.identity.platform-header`） | `Emails` 归一化+校验 → 按 `app_user.email`(citext) 匹配 → 未命中则 **JIT 建号** | `Principal(userId, email, {role})` |
   | `none` | 无 | 恒成功 | `Principal(00000000-0000-0000-0000-000000000001, "Local User", {USER})` |

3. **platform 不建 `identity_link` 表**：外部标识就是邮箱，首次出现时用 `AuthUserRegistrar.provisionExternal` 建号（`app_user` + `user_profile` 原子写、随机口令哈希使其无法口令登录）；并发首访靠 `app_user.email` 唯一索引兜底，`DataIntegrityViolationException` 由 provider 在**非事务上下文**回读既有行。
4. **platform 的信任边界必须显式声明**：反代负责完成统一登录、注入身份头，并**剥离客户端自带的同名头**；实例**不得被直连**。provider 构造时打 WARN 把这条约束写进运行日志。这是该模式唯一的安全假设，配置注释、`.env.example` 与本文三处同时记录。
5. **none 模式的 bootstrap 用户用固定 UUID**（不是字符串 `local`），邮箱 `local@annona.local`；由 `NoneModeUserBootstrapper`（`ApplicationRunner`，仅 none 模式装配）在**启动期**幂等创建，而非首次请求惰性创建——启动即就绪，消除首个 `/api/me` 的建号竞态，失败也落在启动日志而非用户可见的 500。
6. **对 `/api/**` 强制鉴权**，白名单为 `/api/auth/register`、`/api/auth/login`、`/api/auth/logout` 与前缀 `/api/meta/`。过滤器 `shouldNotFilter` 跳过非 `/api/**`（静态资源、actuator）：给静态资源加 Redis 往返没有意义，且强制鉴权会把 SPA 深链 404 伪装成 1004。
7. **未认证拒绝经 `HandlerExceptionResolver` 复抛 `BusinessException(UNAUTHORIZED)`**（HTTP 200 + `Result.error(1004)` + traceId），复用 `GlobalExceptionHandler` 唯一出口，不在过滤器里手写 JSON。`TraceIdFilter` 是 `HIGHEST_PRECEDENCE`，早于本过滤器，拒绝响应仍带 traceId。
8. **保留 `Optional<Principal>` 签名**，过滤器只消费 `id()`；`SESSION_EXPIRED` 语义与 P1a-01 完全一致（主体视图由 `CurrentPrincipalArgumentResolver` + `UserQueryService` 从 DB 补齐，用户行消失即 `SESSION_EXPIRED`）。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 新增 `identity_link(provider, external_id → user_id)` 表 | 为多 IdP / 邮箱解耦预留，但 v1 只有一个外部身份源、设计文档 §5.3 也无此表；代价是 V2 迁移 + 实体 + 仓储 + 映射，把 1 人日预算全吃掉。触发条件见"何时重新评估" |
| `platform` 只做装配骨架（不真正认证） | 验收项"三种 mode 下同一套业务代码都能跑"落空，等于没做 |
| `none` 保留 `Principal.id = "local"` | 与全部 `user_id` 外键不自洽（`app_user.id` 是 UUID），需再加一层字符串→UUID 映射，反而更绕 |
| 让 `app_user.password_hash` 可空以表达"免密账号" | 为一个模式改基线表结构不值当；随机口令哈希同样达到"口令登录不可行"的效果，且不动 schema |
| 把 SPI 改为 `Optional<String>`（只返回 userId） | 语义更窄但 SPI/fake/冒烟测试/文档全要改，且未来 OAuth 实现本可自带 displayName/roles；还会诱使把 DB 读取搬进 provider，让静态资源与心跳也付一次 DB 往返 |
| 继续"只解析不强制" | `SessionAuthFilter` 注释里承诺的 P1a-02 范围落空，P1a-03 起每个受保护接口都得自己抛 `UNAUTHORIZED`，口径必然漂移 |
| 过滤器手写 JSON 拒绝响应 | 绕过全局异常出口，响应形状（code/traceId）容易与 `Result` 漂移；测试与前端拦截器契约都要维护两份 |
| 现在加 `GET /api/auth/config` 暴露 mode 供前端分支 | 前端当前**没有登录页、没有任何 API 调用**，"none 无登录页直达首页"天然成立；提前暴露属投机，推迟到登录页落地那一批 |

## 后果与约束

1. **部署 platform 模式即接受一条安全前提**：反代剥离客户端同名头 + 实例不可直连。违反则任意客户端可伪造身份。运维侧必须保证；代码侧只能 WARN，无法自证。
2. **`/api/**` 现在是默认拒绝**。新增任何 API 端点，若需未登录可达，必须显式加入 `SessionAuthFilter` 白名单——这是有意的摩擦（默认安全）。
3. **platform 模式存在"邮箱即身份"的固有风险**：邮箱变更即换人。当前接受，因为 v1 无第二个身份源；触发条件见下。
4. **`identity` 模块新增 `provider` 子包**，依赖方向仍为 `modules/identity/provider → modules/identity/{service,repository,entity,dto} → spi/common`；不得让 provider 依赖 `infrastructure`。`io.annona.modules.identity` 顶层 `package-info.java` 已声明模块职责，子包不重复。
5. **文档一致性**：`IdentityProvider` 类注释、设计文档 §5.3、`application.yaml`、`.env.example` 四处关于模式与凭据的描述必须与本文一致（本批已同步）。
6. **none 模式下 `/api/auth/register|login|logout` 仍可达但语义失效**（身份被强制为 bootstrap 用户）；`user_session` 审计投影在 none 模式无意义。当前不特殊处理，见下。

## 何时重新评估

- **出现第二个身份源，或要做托管版 OAuth（GitHub / 微信）**：此时引入 `identity_link(provider, external_id, user_id)`，把"邮箱即身份"换成显式联合。
- **登录 UI 落地那一批**：决定 none 模式是否直接拒绝 `register/login/logout`，以及是否新增 `GET /api/auth/config` 暴露 mode。
- **单机（none）升多人（local）**：bootstrap 账号的既有数据归属需要"认领账号"流程；本批不做，出现真实升级需求时再做。
- **反代方案变化（如改用带签名/JWT 的身份头）**：当前信任模型是"网络层不可绕过的明文头"，若改为可携带签名的头，可把信任边界从网络层下沉到应用层，重新评估 `platform` 实现。