# 数据导出跨模块方案（DATA_EXPORT_PLAN）

- 日期 / 状态：2026-10-03 / Proposed（自 P2-07 拆分，用户裁决）
- 承接：P2-07 验收「导出能下载到含 identity 全表 + S3 文件的 zip」；identity ADR §后果 6（导出覆盖 7 张身份表 + 全部业务表 + S3 原始文件）
- 相关：[profile-avatar-adr](../specs/2026-10-03-profile-avatar-adr.md)、[identity-credential-storage-adr](../specs/2026-09-25-identity-credential-storage-adr.md)、[dockerless-local-dev-adr](../specs/2026-09-25-dockerless-local-dev-adr.md)

## 1. 为什么不在 P2-07 里直接做

导出要读 **20+ 张带 `user_id` 的业务表**、跨 ~10 个模块，属 AGENTS「跨 ≥3 模块改造另开本 plan」的情形；且各模块现有 `XxxQueryService` 均为单域窄接口，没有为导出设计的读口。现状下强行收口 = 要么 account 直接 import 各模块 repository（违反依赖方向），要么给 zip 塞一个只做半张表的假导出（违反诚实呈现）。**导出与两段式硬删除共享同一张 `user_data_request` 表和同一条消费管线，本 plan 一并覆盖 DELETE 的收口。**

## 2. 结论（已定，不重复讨论）

- **复用 V1 `user_data_request`**（`type∈EXPORT|DELETE`、`status PENDING→RUNNING→DONE/FAILED`、`file_object_key`、`scheduled_purge_at`），**不新建迁移**。
- **管线走既有基建**：生产端继承 Stream 模板投递 `export:task`；消费端逐表读 → 每表一个 NDJSON 文件 + `manifest.json`（表清单、行数、schema 版本）→ zip → `ObjectStorage.put(exports/{userId}/{taskId}.zip)` → 置 DONE。实体已删除时 ACK 丢弃。
- **跨模块读的唯一合法路径**：各模块为导出补 `ExportContributor`（模块内实现，声明"我贡献哪些表、怎么按 user 读"），由 shared 的注册表聚合——**不新增同步跨模块调用边，不用 QueryService 硬凑 20 张表**。注册表机制届时补一条 ADR（触发：新增 SPI 级扩展点）。
- **下载**：`ObjectStorage` 端口扩 `presignGet(key, ttl)`（SDK 只进 infra），DONE 后给 24h 预签名链接；头像的代理字节端点届时可切 presign（见 profile-avatar-adr 重新评估条款）。
- **S3 原始文件**：按 owner 前缀列举对象并入 zip；对象数超阈值时 zip 只含清单与 DB 数据、文件走批量 presign 链接（取舍届时定）。
- **限流**：本 plan 落地时与 `@RateLimit` 基建（register/login 同样欠着）一并接，不单独造轮子。

## 3. 出口条件（可验证）

1. `ExportFlowIT`（docker 组）：创建 EXPORT 请求 → 消费 → `file_object_key` 对象存在且可 HEAD → DONE；DELETE 两段式（软删即刻不可见、宽限到期物理删）同 IT 覆盖。
2. 清单机检：扫描全库含 `user_id` 的业务表与 `ExportContributor` 注册清单比对，**新增表未登记导出即 CI 红**（照基线表清单登记先例——四次假红换来的教训，必须在同一批落地）。
3. 表清单行覆盖验收：identity 7 表 + 全部业务表 + S3 文件在 zip 内可点数。
4. 端到端取证依赖真实 S3：本机 `mvnw verify`（unit+slice）+ CI docker-it；presign 可 HEAD 属 CI 凭证。

## 4. 规模与排期建议

估 3–4 人日（消费管线 1 · ExportContributor ×10 模块 1.5 · presign 端口与 S3 列举 0.5 · IT 与机检 0.5–1）。**建议挂在 P5 打磨发布前**（数据可携是发布诚实性的一部分），不插队 P3 语音 / P4 编排。

## 5. 借鉴落点（开工先扫，AGENTS §4）

- 🅢 `server/` 与 `src/app/api/user/`（导出/删除的用户数据组织与 prisma 全表读）；🅖 `infrastructure/export/PdfExportService.java`（产物打包形状只作参照，zip 流式组装上游没有）。地图无本板块行，**开工时把确切路径回写借鉴地图 E 表**——地图不完整本身就是缺陷。
