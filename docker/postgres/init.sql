-- docker-entrypoint-initdb.d 只在**空数据卷首次启动**时执行；已有数据卷不会重跑。
--
-- 这里的职责边界要划清：
--   * 本文件只做 superuser 权限才能干的事——CREATE EXTENSION。
--   * 建表与业务迁移全部走 annona-server 的 Flyway V1__baseline.sql（应用启动时由
--     应用账号跑）。V1 里也写了 `CREATE EXTENSION IF NOT EXISTS vector / citext`，
--     因为**非 compose 部署**（用户直连云上 PG）时没有 initdb 阶段，需要 V1 兜底。
--   * 两处 `IF NOT EXISTS` 语义等价，谁先跑谁生效，不冲突。
--   * **pg_trgm 是个例外**：V5 创建它时刻意不带 insufficient_privilege 容错 DO 块
--     （吞掉权限错误后，同一文件里的 gin (content gin_trgm_ops) 会抛一个与真实原因
--     无关的语法错）。所以云上托管 PG 必须由超户/控制台**预先装好 pg_trgm**，
--     否则应用在迁移阶段拒绝启动（见 specs/2026-09-25-chinese-keyword-search-adr.md 修订第 6 条）。
--
-- 为什么扩展要放在 initdb 而不是 V1：
--   CREATE EXTENSION 需要 superuser 或角色有 pg_create_extension 权限。云上托管 PG
--   经常给应用账号一个受限角色，V1 里跑就会 fail。initdb 阶段用的是容器内的
--   $POSTGRES_USER 超户，权限一定够。
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS citext;
-- P1a-07（V5）的关键词通道兜底匹配；CI 的 pgvector/pgvector:pg16 镜像自带 contrib，
-- 与本行语义等价。FlywayExtensionGuard.REQUIRED_EXTENSIONS 也已把它列为启动必检项。
CREATE EXTENSION IF NOT EXISTS pg_trgm;
