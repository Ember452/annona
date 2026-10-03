# ADR: P2-07 个人资料与头像落在 identity、复用 V1 表、代理上传而非 presign

- 日期 / 状态：2026-10-03 / Accepted
- 相关：[identity-credential-storage-adr](./2026-09-25-identity-credential-storage-adr.md)、[结构 §4](../annona-项目结构.md)、[数据导出方案](../plans/DATA_EXPORT_PLAN.md)

## 背景

P2-07「个人主页与设置页整合」原收尾计划假设：新建 `account` 模块 + `V18__avatar_history.sql` + `V19__data_export_task` 两张新表 + presign 直传。
动手前按预案查 schema，发现三条前提与仓库现实冲突：

1. **表已在 V1**：`avatar_change`（头像历史，支持一键回滚）与 `user_data_request`（`type∈EXPORT|DELETE` + `status` 四态 + `file_object_key` + 30 天宽限 purge）V1 baseline 已建，只是**零 Java 代码使用**。新建 V18/V19 会与之重复，且触碰「已应用迁移禁改」红线。
2. **归属已在文档**：结构 §4 明写 identity 的 `controller/` 承担「注册/登录/会话/**资料/头像** REST」。新造 `account` 模块与既有边界冲突。
3. **presign 成本**：`ObjectStorage` 端口无 `presignGet`；上游 🅢 用 presign 让浏览器直传 OSS，但 annona 自家 knowledge/resume 的既有范式是**服务端代理 `put`**。为头像单开 presign 端口 = 为一个功能提前扩对外契约。

## 决策

- **资料 + 头像落在 `identity` 模块**，不新建 `account`：`ProfileService`/`AvatarService` + `MeController` 扩展六个 `/api/me/**` 端点（profile GET/PATCH、avatar 上传/history/rollback/字节回读）。
- **复用 `avatar_change` + `user_profile.avatar_object_key`**，不新增迁移；补 `AvatarChangeEntity`/`Repository` 把已有表接上代码。
- **头像走服务端代理上传**（`ObjectStorage.put`，同 ResumeUploadService：事务外存储、DB 失败删孤儿对象），展示用 `GET /api/me/avatar` 回读字节（img src 自带同源 Cookie），**不引入 presign**。
- **数据导出拆出 P2-07**，升格为跨 ≥3 模块的独立方案（见 [DATA_EXPORT_PLAN](../plans/DATA_EXPORT_PLAN.md)），复用 `user_data_request` 而非新表。
- **导出/头像暂不加限流**（用户裁决）：与现状一致（register/login 亦无限流，README 已声明公网部署须自备反代限流）。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 新建 `account` 模块 | 与结构 §4「资料/头像归 identity」冲突；为一个二级页新增顶层模块是过度设计（AGENTS §3.2） |
| 新建 `V18__avatar_history` / `V19__data_export_task` | V1 已有同义表；重复建表违反存储 ADR 且触碰迁移冻结红线 |
| presign 直传（照搬 🅢） | 需为单一功能扩 `ObjectStorage` 对外契约；annona 自家上传已是代理范式，代理更一致、presign 随导出方案再统一评估 |
| 本轮一并做完数据导出 | 导出读 20+ 张跨模块表，按 AGENTS §4「跨 ≥3 模块改造另开 plan」；塞进 P2 收尾会绕过治理且本机无法端到端验证 |

## 后果与约束

- 头像历史语义（保留最近 1 张、回滚即当前↔历史交换）借 🅢，但传输方式不借——后续若接入 presign 需回改本 ADR 与 `ObjectStorage` 契约。
- 数据导出**不在 P2 交付范围**：P2 出口条件的「导出能下载 zip」转由 [DATA_EXPORT_PLAN](../plans/DATA_EXPORT_PLAN.md) 承接，P2 阶段总结须如实标注该条未闭合。
- `/api/me/**` 无 `@RateLimit`：与 register/login 同处「基建未落地」状态，`@RateLimit` 随其 ADR 统一接入。

## 何时重新评估

- 头像需公网直链或第三方读 → 触发 `ObjectStorage.presignGet` 端口扩（与导出 presign 合并做）。
- DATA_EXPORT_PLAN 开工 → 复用 `user_data_request` 的具体状态机与跨模块读口届时补 ADR。
