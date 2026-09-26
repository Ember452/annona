# ADR: S3 兼容对象存储采用 PGSTY Silo 镜像（MinIO 社区镜像的社区延续线）

- 日期：2026-09-26
- 状态：Accepted
- 相关：[2026-09-25-storage-single-postgres-adr.md](./2026-09-25-storage-single-postgres-adr.md)（S3 在整体存储分工中的角色）、[../reports/P0-骨架-阶段总结.md](../reports/P0-骨架-阶段总结.md) §5 D13（本决策关闭的技术债）、开发计划 §P1a-05

## 背景

MinIO 上游终止了社区发行：2026-02-13 仓库 archive、2026-09-11 Docker Hub 上 `minio/minio` 与 `minio/mc` 整仓删除（`bitnami/minio` 同步消失），所有 tag 返 404。quay.io 副本停在 2025 年且不再更新，**匿名拉取返 401**（2026-09-26 经 `quay.io/api/v1/repository/minio/minio/tag/` 实测确认，与 B5 时 CI runner 上的现象一致）。这直接打在 annona 的核心承诺上：`docker compose up` 必须在全新机器一条命令拉起完整栈（设计文档 §13）。P0 期间的临时处置是把 `storage` 门控在 compose profile `s3` 后面（D13），不阻塞出口②的首页探活。

选型诉求只有一条：**可匿名拉取、持续维护、S3 协议与数据格式兼容**——业务代码走 AWS SDK 的 S3 协议，换实现的代价必须只落在 compose 的 `image:` 行上。

## 决策

**采用 PGSTY Silo（`docker.io/pgsty/silo`，原名 `pgsty/minio`，2026-08-06 更名）作为 storage 服务的镜像**，按 release pin 版本（当前 Server `20260903`），禁用 `latest`。

- 兼容契约按 Silo 官方承诺保留：`MINIO_*` 环境变量、`minio_*` metrics、`x-minio-*` 头、`/minio/*` 路由（含 healthcheck 路径 `/minio/health/live`）、`.minio.sys` 数据格式。compose 现有 env 与 healthcheck **不改**。
- 完整 web console 保留（上游社区版已砍成 stub，Silo 恢复了它）；多架构镜像（amd64/arm64）；每 release 附 SBOM、checksum 与 Sigstore 签名；修复带公开 advisory，release 节奏承诺 1–2 个月、至多一个季度。
- 许可 **AGPL-3.0-or-later、无 CLA**，且项目纲领明写"许可不可变更"——与 annona 的 AGPL-3.0 及 self-host 分发模式兼容；应用侧经 `minio-go` / AWS SDK 访问，无传染问题。
- **启用时机不变**：仍在 P1a-05（上传链）落地时执行 un-gate（删 `profiles:` 行 + server 加回 `S3_*` env 与 `depends_on: storage`），本 ADR 只关闭"选谁"，不提前改运行时行为。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 继续 `quay.io/minio/minio` 冻结 tag | 匿名 pull 实测 401，直接堵死 CI compose-smoke 与用户 self-host；且冻结分支无 CVE 修复 |
| 从源码自建 MinIO 镜像 | 可行但把"维护一个上游已停更的冻结分支 + 自托管镜像"变成项目长期义务，对单人开发是持续负担；Silo 做的正是这件事且已有公开维护记录，没有理由自己重做 |
| Garage（MIT） | 存储格式为私有设计，从 MinIO 迁入需 copy-all 迁移；运维模型（layout、zone 配置）与 MinIO 心智完全不同，"存储选型 ADR"退路变成两套知识 |
| SeaweedFS（Apache 2.0） | master/volume/filer/swift 多角色组件，面向大规模对象场景；annona 的 self-host 是单节点小容量，复杂度与其优势区间错配 |
| 云厂商 S3 / 仅支持 RustFS | 破坏"不依赖任何云服务即可完整部署"的 self-host 承诺；RustFS 可作后补兼容项（协议同为 S3），但镜像可得性与维护记录本次未验证，不预先承诺 |

## 后果与约束

1. **P1a-05 开工前有两个必做实测**（本机无 Docker，均在 CI 执行）：① runner 匿名 `docker pull pgsty/silo:<pin>` 成功；② `MINIO_DEFAULT_BUCKETS` 自举建桶在该 fork 上真实生效——B4 时"不建 init container"的决策前提是官方镜像行为，fork 后属于**待验证假设**（若失效则补一个一次性 `mcli mb` init 容器，偏离回写本文件"后续修订"）。
2. compose/CI **只 pin release tag**，跟进方式按 Silo 自家纪律执行："每次当作下游升级，读 release notes，留回滚路径"。
3. 数据可带性：`.minio.sys` 格式与上游 MinIO 及兼容实现一致，未来若 Silo 停更，换实现的迁移成本限于配置而非数据重灌。
4. 应用代码一律经 S3 协议访问（`Retriever`/file 抽象在 `annona-infrastructure`），**禁止引入任何 Silo/MinIO 私有 API**，保证本 ADR 可被无痛推翻。
5. 阶段总结 D13 状态改为"选型已定"，剩余动作（un-gate + S3 client bean + `S3_*` env 接回）留在 P1a-05 任务内。

## 何时重新评估

- Silo 连续两个承诺周期（> 一个季度）无 release，或公开 advisory 记录中断；
- Pigsty 社区托管状态发生实质变化（仓库归档、签名/SBOM 纪律停止）；
- MinIO 上游恢复社区镜像发行（Silo 纲领承诺届时收窄范围并把修复回馈上游）；
- 文档量与文件体积增长到单节点对象存储成为瓶颈（触发量级见 storage-single-postgres ADR §何时重新评估）。
