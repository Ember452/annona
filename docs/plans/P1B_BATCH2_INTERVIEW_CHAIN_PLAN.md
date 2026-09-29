# P1b 批 2：面试链（组卷 / 状态机 / 面试中心 / 计量与 Key）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通"开始一场面试 → 逐题作答 → 断线可续 → 交卷幂等 → 用量可查"的最小完整闭环，并为批 3 评估预留数据与幂等契约。

**Architecture:** 面试会话状态机落 PG（冷真值）+ Redis 热快照（冷热分层）；组卷为纯逻辑决策层（配额/难度/去重可单测+golden）；模型计量走 `ModelProvider`/`StreamingChatProvider` 装饰器统一拦截，业务代码零散埋点禁止。Key 加密沿用 2026-09-25 KEK ADR 的三列结构与五用途，本批扩展为六用途（+RERANK）。

**Tech Stack:** Spring Boot（annona-server 四模块）、PostgreSQL + pgvector、Redisson、JPA + 条件 UPDATE fencing、React + TypeScript（annona-web）。

**Spec:** 本计划源自用户批 2 需求 + 以下 ADR（T2/T8/T12 落文件）：`docs/specs/2026-09-29-interview-session-adr.md`、`docs/specs/2026-09-29-llmprovider-metering-adr.md`、`docs/specs/2026-09-25-model-api-key-adr.md`（沿用，不 Supersede）。

## Global Constraints

- 本机无 Docker：`@Tag("docker")` 集测本机不跑，CI `-Dgroups=docker` 执行；不得为本地能跑而 mock SQL/向量或改用 H2（AGENTS §0.9）。
- 日常验证命令：`.\mvnw.cmd -B -q verify`（EXIT=0，含 ArchUnit + JaCoCo 60%/关键包 85%）；前端 `cd annona-web; pnpm typecheck; pnpm lint; pnpm test; pnpm build`。
- 业务异常一律 `BusinessException(ErrorCode.X, msg)`；对外 `Result<T>`；禁止 Entity 出前端；禁止 Service 散落 `@Value`；手写构造器注入（无 Lombok）。
- LLM/S3/外部 HTTP 不得进 DB 事务；异步写用 afterCommit + `aiIoExecutor`；纯计算加密用 `cpuExecutor`。
- Java 4 空格 / 前端 2 空格；无通配符 import；测试 `@DisplayName` 中文；核心代码 Javadoc 含取舍记录。
- 预置主键实体：id 由 `UUID.randomUUID()` 赋值，`save()` 走 merge——需要 DB default 时用返回值（AGENTS §4）。
- 已应用 Flyway 迁移禁改；新表必须同步登记 `FlywayBaselineIT` 清单（T0 门禁机检）。
- 提交规范：Conventional Commits + `Task: P1b-<nn>` + DCO `-s`；message 用 `-F` 文件；docs 与 feat/fix 分离。**未经用户明确指令不得 commit/push**——本计划中"Commit"步骤一律先向用户请示。
- 错误码只增不 reuse：interview 续 2701+；usage 2800–2899；llmprovider 2900–2999。
- 借鉴扫描义务（AGENTS §4）：每个 feat 任务动手前先读下方给出的上游路径，4 行借鉴说明写进该 commit 正文。

## 修订决策（相对用户原计划，已拍板"全局最优"口径）

| # | 原计划 | 修订为 | 理由 |
|---|---|---|---|
| M1 | 前置条件"#19 待合并" | 已满足：`origin/main`=`449bfd2`（Merge PR #19），直接从 main 拉 `feat/p1b-batch2-interview-chain` | 实测 ls-remote/merge-base |
| M2 | 题目源 = questionbank `QuestionQueryService`（当作已有） | T4 第一步显式新建该只读端口 | 现仓只有 QuestionBankService/GenStateService/GenerationService |
| M3 | 去重向量判"复用 `spi.retrieval.Retriever` 拿候选历史题干相似分" | `Retriever` 检索对象是 `kb_doc_chunk`，题目无向量——改为 V9 给 `qb_question` 加 `embedding vector(1024)`，出题时 best-effort 嵌入；去重 = 候选向量 × 历史题向量的 pgvector cosine 查询；关键词判 = `Tokenizer` 切词 + ILIKE 兜底 | 原方案在现架构下拿不到"题干相似分"；加列零回填、老题 NULL 自动降级 |
| M4 | "借 `@RateLimit` 可重复注解先例，别手写散落限流" | annona 无该注解（仅注释将来时）；每日配额收敛为 common 的 `DailyQuotaService` 单点组件，注解骨架推迟 | 不引用不存在的基础设施；配额与瞬时限流是两个概念 |
| M5 | V10 单列 `api_key_enc BYTEA`、purpose 含 rerank 无 evaluator | 三列 `api_key_nonce BYTEA / api_key_cipher BYTEA / kek_version`（KEK ADR 原样）；purpose 六分 `chat/embedding/rerank/tts/asr/evaluator`；metering ADR 标注"扩展而非推翻 2026-09-25 ADR" | kek_version 是轮换机制支点；EVALUATOR 批 3 必用，现在丢=将来 V12 |
| M6 | ABANDONED 无转移入口 | 新建会话时同方向旧 RESUMABLE **自动置 ABANDONED**（服务端文案提示）+ 手动 `POST /sessions/{id}/abandon` | 防 RESUMABLE 堆积拖垮恢复扫描，不加定时任务 |
| M7 | `2702 SESSION_CAPACITY_INSUFFICIENT` | 容量不足复用既有 `2604 QB_QUESTION_CAPACITY_INSUFFICIENT`；2702 释放 | 同一失败语义不双码（批 1 容量校验已占 2604） |
| M8 | 幂等键=session_id+evaluator_version | 幂等的**唯一守门**是 `RESUMABLE→COMPLETED` 条件 UPDATE affected-rows=1；只有成功线程写 evaluator_version/返回 finalize 结果 | 键本身不防并发双写，fencing 才防 |

## 文件结构总览

```
scripts/ci/check-migration-inventory.py          # T0 门禁
scripts/ci/test-check-migration-inventory.py     # T0 可证伪自测
.githooks/pre-commit                             # T0 追加第五道
annona-server/src/main/resources/db/migration/
  V9__interview_session.sql                      # + qb_question.embedding
  V10__llm_provider.sql
  V11__token_usage.sql
annona-server/src/test/java/io/annona/integration/FlywayBaselineIT.java   # 登记 7 张新表
annona-server/src/main/java/io/annona/modules/
  questionbank/service/QuestionQueryService.java          # M2 新增只读端口
  interview/orchestrator/          # plan/ pack/ session/ state/ cache/ controller/（package-info 已预留落点）
  usage/                           # T12
  llmprovider/                     # T12
annona-common/src/main/java/io/annona/common/
  quota/DailyQuotaService.java     # M4（端口形态，Redis 实现在 infra）
  crypto/ApiKeyCipher.java         # 端口（T2 建，T12 用）
annona-infrastructure/src/main/java/io/annona/infrastructure/
  cache/InterviewSessionCache.java  # Redis 热快照实现（端口在 orchestrator，经 common 抽象）
  crypto/ApiKeyCipherImpl.java      # AES/GCM（infra package-info 已预留 crypto/）
  quota/RedissonDailyQuotaCounter.java
annona-web/src/
  api/interview.ts  types/interview.ts  constants/routes.ts(改)  pages/interview/index.tsx(改)
docs/specs/2026-09-29-interview-session-adr.md   # T2
docs/specs/2026-09-29-llmprovider-metering-adr.md # T12
docs/annona-开发计划.md / annona-项目结构.md / README.md   # T13 同步
```

---

### Task 0: PR0 迁移基线机检门禁

**Files:**
- Create: `scripts/ci/check-migration-inventory.py`（重写，勿照搬 `%TEMP%\annona-batch2-gate-backup\` 含 `qb_tmp_gate_test` 的探针版；可参考其结构）
- Create: `scripts/ci/test-check-migration-inventory.py`
- Modify: `.githooks/pre-commit`（第五道，追加在第二道之后、gitleaks 之前）

**Interfaces:**
- Produces: `python scripts/ci/check-migration-inventory.py`（exit 0/1 + stderr 缺漏清单）；pre-commit 第五道在其退出码非 0 时拒绝 commit。

- [ ] **Step 1: 写被检脚本**

逻辑（stdlib-only，无第三方依赖，PyYAML 不需要）：
1. 扫描 `annona-server/src/main/resources/db/migration/V*__*.sql`，正则 `CREATE TABLE (?:IF NOT EXISTS )?([a-z_0-9]+)` 提取表名，按版本号分组（跳过语句注释行）。
2. 解析 `annona-server/src/test/java/io/annona/integration/FlywayBaselineIT.java` 中 `containsExactlyInAnyOrder(` 之后到 `);` 的字符串字面量集合为"已登记表"。
3. 差集非空 → stderr 打印缺失表与所属迁移文件、exit 1；一致 → exit 0。迁移目录/测试文件不存在 → exit 2（区分配置错误与违规）。

- [ ] **Step 2: 写可证伪自测**

`scripts/ci/test-check-migration-inventory.py`：用 `tempfile.TemporaryDirectory` 造出 `db/migration/V1__a.sql`（`CREATE TABLE alpha;`）+ 假 FlywayBaselineIT 源文件。三个用例（顺序断言，全过才 exit 0）：
1. 新增 `V9__fake.sql` 含 `CREATE TABLE x_tmp;`，清单未登记 → 调 `check(...)` 断言返回码 == 1 且 stderr 含 `x_tmp`；
2. 把 `"x_tmp"` 加进假清单 → 断言返回码 == 0；
3. 删掉迁移目录参数指向不存在路径 → 断言返回码 == 2。
为满足可测试性，`check-migration-inventory.py` 把主逻辑做成 `def check(migration_dir: str, it_file: str) -> int`，`if __name__ == "__main__": sys.exit(check(默认路径...))`。

- [ ] **Step 3: 运行自测**

Run: `python scripts/ci/test-check-migration-inventory.py` → Expected: exit 0，stdout 三个 PASS。
再跑真实检查：`python scripts/ci/check-migration-inventory.py` → Expected: exit 0（当前 V1–V8 与清单已一致，V8 两表已登记）。

- [ ] **Step 4: pre-commit 追加第五道**

插在第二道（check-test-config-shadowing）之后：

```sh
# 第五道：迁移↔基线清单登记守卫。FlywayBaselineIT 的 containsExactlyInAnyOrder 是全库
# 清单断言，新表漏登记会让 CI docker 组假红（V2/V4/V6 三连踩，AGENTS §4 约定→机检第 5 例）。
if git diff --cached --name-only | grep -qE '^annona-server/src/(main/resources/db/migration/|test/java/io/annona/integration/FlywayBaselineIT)'; then
  if ! command -v python >/dev/null 2>&1; then
    printf '%s\n' "pre-commit: 本次改了迁移或基线清单，需要 python 跑登记一致性检查。" >&2
    exit 1
  fi
  python scripts/ci/check-migration-inventory.py || exit 1
fi
```

注意：pre-commit 头部注释"四个动作"同步改成"五个动作"（改代码必须同改注释）。

- [ ] **Step 5: 验证 hook 语法**

Run: `sh -n .githooks/pre-commit`（Git Bash 可用时）；Windows 下退化验证：人工复读 + 在真实仓库暂存一个假迁移文件试 commit（随后 restore）。
Commit（待用户授权）：`ci: add migration-inventory pre-commit gate with falsifiable self-test`，`Task: P1b-00`。

---

### Task 1: 从 main 拉批 2 分支

- [ ] **Step 1:** `git -C D:\DEVELOP\java\annona switch main; git pull --ff-only; git switch -c feat/p1b-batch2-interview-chain`。验证：`git log --oneline -1` 应为 `449bfd2` 或其后代。

---

### Task 2: V9 迁移 + interview-session ADR

**Files:**
- Create: `annona-server/src/main/resources/db/migration/V9__interview_session.sql`
- Modify: `annona-server/src/test/java/io/annona/integration/FlywayBaselineIT.java:64-71`（清单追加 `"interview_session", "interview_answer"`）
- Create: `docs/specs/2026-09-29-interview-session-adr.md`
- Modify: `docs/README.md`（登记新 ADR）

**Interfaces:**
- Produces: 表 `interview_session` / `interview_answer`、列 `qb_question.embedding vector(1024)`；实体列名是 T3/T4/T5 的编译契约。

- [ ] **Step 1: 写 V9 DDL（全文，注释密度对齐 V8）**

```sql
-- V9__interview_session.sql — P1b-04/05：面试会话与作答 + 题目向量列
-- 依据：docs/specs/2026-09-29-interview-session-adr.md（含 M3/M6/M7 修订）。
-- 形状借 🅖 InterviewSessionEntity/InterviewAnswerEntity + 🅜 flow 状态机，三处按 annona 改：
--   ① 状态只有 RESUMABLE/COMPLETED/ABANDONED（🅜 的 PREPARE/RUNNING 合并进 RESUMABLE——
--      组卷即开始，无独立备考态）；
--   ② evaluator_version 交卷时写死 'v1'（批 2 只留幂等契约，批 3 评估消费；TEXT 非枚举，
--      评分器升版不改表）；
--   ③ 追问定位沿用 ADR 先例 (question_id, follow_up_index)，0 为主问题。

CREATE TABLE interview_session (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    direction_id     UUID NOT NULL REFERENCES direction(id),
    status           VARCHAR(16) NOT NULL,
    plan             JSONB NOT NULL,
    current_index    SMALLINT NOT NULL DEFAULT 0,
    total_count      SMALLINT NOT NULL,
    evaluator_version TEXT NULL,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_session_status CHECK (status IN ('RESUMABLE', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT chk_session_index_bounds CHECK (current_index >= 0 AND current_index <= total_count)
);
COMMENT ON TABLE interview_session IS '面试会话：DB 为冷真值，Redis 快照仅作热路径（interview-session-adr §冷热分层）；状态转移全部走条件 UPDATE（fencing，questionbank 先例）';
COMMENT ON COLUMN interview_session.plan IS 'InterviewPlan 快照 JSONB（服务端校验后的定稿，非客户端原文）；批 3 评估消费，schema 版本随 plan JSON 内 v 字段';
COMMENT ON COLUMN interview_session.evaluator_version IS '交卷时写 ''v1''（批 2 语义），幂等键 = session_id + evaluator_version；真正评分批 3 落地';
COMMENT ON COLUMN interview_session.current_index IS '续面恢复位：0..total_count（等于 total_count 表示全部作答完待交卷）';
CREATE INDEX idx_session_user_direction_status ON interview_session (user_id, direction_id, status);
CREATE INDEX idx_session_resumable ON interview_session (user_id) WHERE status = 'RESUMABLE';

CREATE TABLE interview_answer (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES interview_session(id) ON DELETE CASCADE,
    question_id     UUID NOT NULL REFERENCES qb_question(id),
    follow_up_index SMALLINT NOT NULL DEFAULT 0,
    answer_text     TEXT NULL,
    answer_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    submitted_at    TIMESTAMPTZ NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_answer_slot UNIQUE (session_id, question_id, follow_up_index),
    CONSTRAINT chk_answer_status CHECK (answer_status IN ('PENDING', 'SUBMITTED'))
);
COMMENT ON TABLE interview_answer IS '逐题/逐追问作答；uq_slot 是交卷幂等的 DB 级兜底（重复 finalize 不产生双份记录，interview-session-adr §幂等）';
CREATE INDEX idx_answer_session ON interview_answer (session_id);

-- M3：题目向量列。出题落库时 best-effort 嵌入（EmbeddingProvider 未配置/失败 → NULL 不阻塞）；
-- 组卷历史去重的向量判只对非 NULL 行生效，NULL 行降级关键词判。HNSW 与 kb_doc_chunk 同参。
ALTER TABLE qb_question ADD COLUMN embedding vector(1024) NULL;
COMMENT ON COLUMN qb_question.embedding IS '题干向量（1024 冻结，同 retrieval 口径）；JPA 实体刻意不映射（kb_doc_chunk.embedding 先例），写入走原生 SQL ::vector';
CREATE INDEX idx_qb_question_embedding ON qb_question USING hnsw (embedding vector_cosine_ops);
```

- [ ] **Step 2: FlywayBaselineIT 清单追加两表**（在 `"qb_question", "qb_generation_task");` 后改逗号续行，注释格式照抄 V8 行风格）+ 更新类头 javadoc 的"16 张表"计数与括号构成（改代码必须同改注释）。

- [ ] **Step 3: 跑门禁** Run: `python scripts/ci/check-migration-inventory.py` → Expected: exit 0（Step 2 已登记；若先跑会 exit 1，正是 T0 自测之外的实证）。

- [ ] **Step 4: 写 interview-session ADR（一页，格式按 AGENTS §6）**

必含小节：背景（批 2 范围、批 3 消费面）；决策——数据模型（上面三件事）、**InterviewPlan record 契约与服务端校验上限**：

```java
public record InterviewPlan(int v, int totalCount, List<Integer> difficulties, int followUpDepth) {}
```
校验：`v==1`；`totalCount ∈ [1, 20]`；`difficulties ⊆ [1,5]` 非空且长度==totalCount；`followUpDepth ∈ [0,3]`。客户端越界 → `1001`；容量不足 → `2604`（M7，复用批 1）。
**组卷算法**：方向 ACTIVE 题池 → 按 plan.difficulties 逐槽抽取 → 去重双判（向量 cosine ≥ **0.92** 排除，候选×历史窗口 = 该用户近 **90 天**已作答 ∪ 本方向 ACTIVE 池已在池中题；关键词判 = Tokenizer 切词 top-5 实词 ILIKE 全包含排除；embedding 为 NULL 仅关键词判）→ 主问题 + `follow_up_index` 展开追问占位；阈值取值依据写进 `QuestionPackService` Javadoc。**状态机**：RESUMABLE→COMPLETED/ABANDONED 条件 UPDATE；ABANDONED 两入口（M6）。**幂等**（M8 顺序）。**冷热分层**（Redis miss 回落 DB）。否决的备选表（至少四条）：单列密文存 Key（→T12）、题池全文检索判重（Recall 不可控）、新建独立 question_embedding 表（多一个 join 与一致性点，列直加更简）、SingleFlight 用于交卷（写路径不进，批 1 硬约束）。后果与约束：批 3 evaluator 消费 `evaluator_version`；plan JSON 的 `v` 字段是快照版本唯一支点。何时重新评估：题目池 >10 万行时 HNSW 参数；embedding 模型换维时 V<n+1> 重建。

- [ ] **Step 5: `docs/README.md` 登记 ADR；Commit（待授权）**：`feat(interview): add V9 interview session/answer schema with plan ADR` + `Task: P1b-04`。

---

### Task 3: 会话实体 / Repository / 状态机（fencing）

**Files:**
- Create: `modules/interview/orchestrator/session/InterviewSessionEntity.java`、`InterviewAnswerEntity.java`、`InterviewSessionRepository.java`、`InterviewAnswerRepository.java`、`InterviewSessionStateService.java` + 各子包 `package-info.java`（orchestrator 顶层必须有）
- Create: `modules/interview/orchestrator/session/InterviewSessionStateServiceTest.java`（slice，`@Tag("slice")`）

**Interfaces:**
- Consumes: V9 列名。
- Produces（T4/T5 依赖的确切签名）：
  - `InterviewSessionStateService.create(UUID userId, UUID directionId, InterviewPlan plan, List<UUID> questionIds) -> InterviewSessionEntity`（事务内：旧 RESUMABLE 自动置 ABANDONED + 落 session + answer 占位）
  - `boolean submitAnswer(UUID sessionId, UUID userId, int index, String text)`
  - `Optional<FinalizeResult> finalizeSession(UUID sessionId, String userId)`，`record FinalizeResult(UUID sessionId, int answeredCount, String evaluatorVersion)`
  - `boolean abandon(UUID sessionId, String userId)`
  - `ErrorCode` 追加：`SESSION_NOT_FOUND(2701, "面试会话不存在或已过期")`、`SESSION_ALREADY_COMPLETED(2702, "该面试已交卷，请从面试中心查看")`、`SESSION_SLOT_MISMATCH(2703, "作答位置与会话进度不一致，请刷新")`

- [ ] **Step 1: 借鉴扫描**（🅖 `modules/interview/service/InterviewSessionService.java`、🅜 `interview/application/flow/InterviewFlowStateMachine.java`），commit 正文四行说明。
- [ ] **Step 2: 写失败测试** —— 状态机三转移 + 幂等，用 Mockito 切片断言条件 UPDATE 返回值驱动分支：

```java
@Test
@DisplayName("finalize 条件 UPDATE affected-rows=0 时不写 evaluator_version、不重复产出结果")
void finalizeLosesRace() {
    when(sessionRepository.finalizeIfResumable(any(), any(), eq("v1"))).thenReturn(0);
    var result = stateService.finalizeSession(sessionId, "user-1");
    assertThat(result).isEmpty();
    verify(answerRepository, never()).submitAll(any());
}
```

- [ ] **Step 3: 跑测试确认失败** Run: `.\mvnw.cmd -B -q test -Dtest=InterviewSessionStateServiceTest` → Expected: 编译失败（类不存在）。
- [ ] **Step 4: 实现实体与 Service**

实体要点：id 应用侧 `UUID.randomUUID()`；`created_at` 等 `insertable=false`；**save/merge 语义**——需要回读 DB default 时用 `EntityManager.refresh(saved)` 接返回值（AGENTS §4 全仓约定）。Repository 条件 UPDATE（`@Modifying`）：

```java
@Modifying
@Query("UPDATE InterviewSessionEntity s SET s.status = 'COMPLETED', "
    + "s.evaluatorVersion = :evaluatorVersion, s.finishedAt = now(), s.updatedAt = now() "
    + "WHERE s.id = :id AND s.userId = :userId AND s.status = 'RESUMABLE'")
int finalizeIfResumable(@Param("id") UUID id, @Param("userId") UUID userId,
                        @Param("evaluatorVersion") String evaluatorVersion);
```

`finalizeSession` 顺序（M8 写死）：① 取会话，非 RESUMABLE 且已 COMPLETED → 抛 `2702`（幂等重放语义批 3 补结果重放）；② `finalizeIfResumable(...,"v1")` affected==0 → 视为并发败者，重读后按状态给 `2702`/empty；③ affected==1 才 `submitAll`（answer 占位置 SUBMITTED、`uq_answer_slot` 兜底）。事务边界只含这些 DB 写；组卷、LLM 全在事务外。
- [ ] **Step 5: 跑测试通过 + ArchUnit** Run: `.\mvnw.cmd -B -q verify` → Expected: EXIT=0（orchestrator 新包补进 ArchUnit 白名单与 `modules/interview/package-info` 允许依赖声明——package-info 已预留"届时按任务补子包声明"）。
- [ ] **Step 6: Commit（待授权）**：`feat(interview): add session entities and fencing state machine`。

---

### Task 4: QuestionQueryService + 组卷纯逻辑（golden + 85%）

**Files:**
- Create: `modules/questionbank/service/QuestionQueryService.java`（跨模块只读端口，AGENTS §4 例外①先例）
- Create: `modules/interview/orchestrator/pack/InterviewPlan.java`、`QuestionPackService.java`、`QuestionDedupService.java`、`PackRules.java`
- Test: `QuestionPackServiceTest`（纯逻辑单测，无 tag）、`__snapshots__/QuestionPackServiceTest.pack.golden`
- Modify: `modules/questionbank/service/QuestionGenerationService.java`（落库后 best-effort 嵌入）

**Interfaces:**
- Consumes: T3 实体、`Tokenizer`（common/search）、`EmbeddingProvider`（spi，经现有注入路径 Optional 取——门控 bean 禁止硬注入）。
- Produces:
  - `QuestionQueryService.activeByDirection(UUID directionId) -> List<QuestionCandidate>`，`record QuestionCandidate(UUID id, String question, int difficulty, List<FollowUp> followUps)`（followUps 从 JSONB 解析）
  - `QuestionPackService.pack(InterviewPlan plan, List<QuestionCandidate> pool, Map<UUID, StemSimilarity> dedupHits) -> PackResult`，`record PackResult(List<UUID> questionIds, List<String> skippedReasons)`
  - `StemSimilarity(UUID questionId, double cosine, boolean keywordHit)`；`QuestionDedupService.findDedupHits(UUID userId, UUID directionId, List<QuestionCandidate> candidates) -> Map<UUID, StemSimilarity>`（向量判：原生 SQL `1 - (embedding <=> :vec) >= 0.92`；关键词判：Tokenizer 切词 top-5 实词 ILIKE）

- [ ] **Step 1: 借鉴扫描**（🅖 `modules/interview/service/InterviewQuestionService.java`、`model/{InterviewQuestionDTO,HistoricalQuestion}.java`；🅜 `interview/application/**` 组卷），写四行说明。
- [ ] **Step 2: 写失败纯逻辑测试**（配额/难度映射/去重判定三组 + golden 快照；golden 机制沿用仓内既有 retrieval 评测的快照写法，若无既有机制则用 `assertThat(pack).isEqualTo(readResource("golden/pack-basic.json"))` + 首次运行生成）：

```java
@Test
@DisplayName("难度槽位无足量候选时按相邻难度回填，缺口记 skippedReasons")
void difficultyFallback() {
    var pool = List.of(candidate(1, "q1"), candidate(2, "q2"));
    var plan = new InterviewPlan(1, 3, List.of(5, 5, 5), 0);
    var result = packService.pack(plan, pool, Map.of());
    assertThat(result.questionIds()).hasSize(2);   // 宁缺毋滥：缺 1 题不硬凑
    assertThat(result.skippedReasons()).anyMatch(r -> r.contains("难度5"));
}

@Test
@DisplayName("cosine≥0.92 或关键词全包含的候选被剔除")
void dedupRejects() {
    var a = candidate(1, "Redis 持久化机制");
    var b = candidate(2, "Kafka 分区 rebalance 流程");   // id=2 命中向量相似
    var c = candidate(3, "Redis 持久化机制详解");        // id=3 命中关键词全包含
    var hits = Map.of(
        b.id(), new StemSimilarity(b.id(), 0.95, false),
        c.id(), new StemSimilarity(c.id(), 0.40, true));
    var plan = new InterviewPlan(1, 2, List.of(1, 1), 0);
    var result = packService.pack(plan, List.of(a, b, c), hits);
    assertThat(result.questionIds()).containsExactly(a.id());
    assertThat(result.skippedReasons()).anyMatch(r -> r.contains("重复"));
}
```

- [ ] **Step 3: Run → FAIL**；**Step 4: 实现**——`PackRules` 常量类放阈值（`COSINE_EXCLUDE = 0.92`、`KEYWORD_TOKENS = 5`、`HISTORY_WINDOW_DAYS = 90`），每个值 Javadoc 写依据（AGENTS §4 算法类注释规范：阈值取值口径必须能回答"凭什么"）；纯函数、无 IO，`QuestionDedupService` 才碰 DB/Embedding。
- [ ] **Step 5: Run `.\mvnw.cmd -B -q verify`** → Expected: PASS 且 `pack` 包覆盖率 ≥85%（JaCoCo check 按 includes 加关键包规则——本任务即 planner/pack 类 85% 规则的首次落地，同 commit 配好）。
- [ ] **Step 6: 出题嵌入 best-effort**：`QuestionGenerationService` 落库后 `Optional<EmbeddingProvider>` 存在时异步嵌入（`cpuExecutor`/`aiIoExecutor`，失败仅 warn——NULL 降级已写入 V9 注释）。
- [ ] **Step 7: Commit（待授权）**：`feat(interview): add question pack algorithm with vector+keyword dedup`。

---

### Task 5: 会话端点 + 恢复扫描 + 冷热分层

**Files:**
- Create: `orchestrator/controller/InterviewSessionController.java`、`orchestrator/cache/SessionSnapshotPort.java`（端口进 orchestrator，实现 `RedissonInterviewSessionCache` 进 `annona-infrastructure/cache`，借 `RedissonSessionStore` 形态）
- Modify: `InterviewSessionRepository`（加 `findResumable(userId, directionId)`）
- Test: `InterviewSessionControllerTest`（slice）+ `InterviewSessionFlowIT`（`@Tag("docker")`）

**Interfaces:**
- Consumes: T3 StateService、T4 PackService/Plan。
- Produces（前端 T11 的确切契约）：
  - `POST /api/interview/sessions` body `{directionId, plan}` → `Result<SessionView>`；错误：1001（plan 越界）、2100（方向不可见）、2604（容量不足）、2701
  - `GET /api/interview/sessions?status=RESUMABLE` → `Result<List<SessionSummary>>`
  - `GET /api/interview/sessions/{id}` → `Result<SessionView>`（含当前题与进度）
  - `POST /api/interview/sessions/{id}/answers` body `{index, answerText}` → `Result<Boolean>`
  - `POST /api/interview/sessions/{id}/finalize` → `Result<FinalizeView>`（批 2：`{sessionId, answeredCount, status:"COMPLETED", evaluatorVersion:"v1"}`，无评分）
  - `POST /api/interview/sessions/{id}/abandon` → `Result<Boolean>`
  - `SessionView`：`{id, directionId, status, currentIndex, totalCount, totalQuestions 含追问展开数, firstQuestion|null, plan}`——**禁止返回 Entity**。

- [ ] **Step 1: 写失败 slice 测试**（Controller 委托 + plan 校验 400 路径 + SessionView 形状）；实现端点（Controller 只做路由/校验/委托）。
- [ ] **Step 2: 冷热分层**：`SessionSnapshotPort`（`put(sessionId, json, ttl 2h)` / `get` / `evict`）；读路径 Redis miss → DB 重建快照；写路径（answers/finalize/abandon）DB 先行、Redis 尽力失效。**降级**：Redisson bean 缺席（门控）时端口经 `Optional<>` 注入，纯 DB 路径照常装配（AGENTS 门控禁令）。
- [ ] **Step 3: 写 `@Tag("docker")` 集测 InterviewSessionFlowIT**（本机不跑，CI 绿为准）：① 造 6 题 ACTIVE 池 → 建会话 → 作答 2 题 → **模拟重启**（`entityManager.clear()` + 直读 DB 重进）→ 恢复位 == 2 且首题一致；② 同用户连续两场（第一场交卷后开第二场）→ 断言两场 question_id 交集为空且 embedding NULL 题也被关键词判拦下；③ 并发双 `finalize`（两线程）→ `interview_answer` 无重复、DB 中 COMPLETED 恰一条、一方拿 2702。
- [ ] **Step 4: Run `.\mvnw.cmd -B -q verify`**（docker 组默认排除）→ PASS；Commit（待授权）：`feat(interview): expose session endpoints with cold-hot layered resume`。

---

### Task 6–7: 批 2 后端错误码与包声明（并入 T3/T5 交付，不单列提交）

见 T3 错误码块与 package-info 义务；无独立验证步骤。

---

### Task 8: V11 token_usage + V10 llm_provider_config（三列加密结构）

**Files:**
- Create: `V11__token_usage.sql`、`V10__llm_provider.sql`
- Modify: `FlywayBaselineIT.java`（追加 `"token_usage"`、`"llm_provider_config"`）+ javadoc 计数

- [ ] **Step 1: V11 DDL**

```sql
-- V11__token_usage.sql — P1b-10：模型用量记账（借 🅢 schema.prisma::TokenUsage 与 🅖 用量表，
-- 改 surface/tier 二维为 scene/purpose 二维：前者是产品入口口径，我们要的是计费与归属口径）
CREATE TABLE token_usage (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    scene             VARCHAR(24) NOT NULL,
    session_id        UUID NULL,
    provider          VARCHAR(64) NOT NULL,
    model             VARCHAR(128) NOT NULL,
    purpose           VARCHAR(16) NOT NULL,
    prompt_tokens     INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    prompt_hash       VARCHAR(64) NULL,
    evaluator_version TEXT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_usage_scene CHECK (scene IN ('INTERVIEW', 'QUESTION_GEN', 'QA', 'EVALUATION'))
);
COMMENT ON COLUMN token_usage.session_id IS '按场景指向不同宿主（interview_session/qa_session/…），故不设 FK；成本聚合走 (user_id, created_at)，会话钻取走 session_id';
COMMENT ON COLUMN token_usage.prompt_hash IS '请求正文 SHA-256（评估可比性留痕，批 3 消费）；不含正文，无泄漏面';
CREATE INDEX idx_usage_user_created ON token_usage (user_id, created_at DESC);
CREATE INDEX idx_usage_session ON token_usage (session_id);
```

- [ ] **Step 2: V10 DDL**（M5 决策落点）：

```sql
-- V10__llm_provider.sql — P1b-10：用户级 Provider 配置（宽口径；rerank/tts/asr 属提前建设，
-- llmprovider-metering-adr 已记录取舍）。三列密文结构沿用 2026-09-25-model-api-key-adr §决策 2，
-- kek_version 是 KEK 轮换支点（该 ADR 后果条款 4），单列密文方案在 ADR 否决表内，不重开。
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
COMMENT ON COLUMN llm_provider_config.api_key_cipher IS 'AES/GCM 密文；明文永不下发前端、不进日志（KEK ADR §决策 5）';
COMMENT ON COLUMN llm_provider_config.kek_version IS '加密时的 ANNONA_SECRET_KEY 版本；轮换走 annona reencrypt（P 后期命令）';
CREATE INDEX idx_provider_config_user ON llm_provider_config (user_id, enabled);
```

- [ ] **Step 3:** FlywayBaselineIT 登记两表；`python scripts/ci/check-migration-inventory.py` → exit 0。
- [ ] **Step 4:** docker 组集测 `MigrationV9ToV11IT`（断言 partial index 存在、`uq_answer_slot` 双插抛约束、CHECK 六用途拒绝非法值）。本机只保证编译；Commit（待授权）：`feat(interview): add V10 provider config and V11 token usage schema`。

---

### Task 9: ApiKeyCipher 端口 + AES/GCM 实现（纯计算）

**Files:**
- Create: `annona-common/crypto/ApiKeyCipher.java`（端口）、`ApiKeyCipherTest`（纯逻辑单测）
- Create: `annona-infrastructure/crypto/AesGcmApiKeyCipher.java`（infra package-info 已预留 crypto/）
- Create: `annona-infrastructure/.../application-dev.yaml` 侧配置类 `CryptoProperties`（`@ConfigurationProperties`，默认值唯一出处规则——yaml 写 `annona.secret-key: ${ANNONA_SECRET_KEY:}`，dev fallback 仅 dev profile，与 KEK ADR §决策 3 一致）

- [ ] **Step 1: 借鉴扫描** 🅖 `modules/llmprovider/service/ApiKeyEncryptionService.java`（重点抄其 nonce 结构，**删其 DEV_FALLBACK_KEY 静默兜底**——KEK ADR 否决表明确不采用）。
- [ ] **Step 2: 失败测试**：往返 `encrypt→decrypt` 相等；同明文两次加密密文不同（nonce 随机）；nonce 96-bit、GCM tag 128-bit；`kekVersion` 不符时抛 `BusinessException(ErrorCode.AI_API_KEY_INVALID…段 2900)`；掩码形如 `sk-a…z7`（首 4 尾 2，长度 <8 全星）。
- [ ] **Step 3–4: 实现**（`javax.crypto`，零第三方 SDK → 端口按 AGENTS §4 判据放 **common** 而非 spi：不会有第三方实现方）。解密结果不进缓存（KEK ADR §决策 5）。`cpuExecutor` 只被 Service 层调用方使用，Cipher 本身同步纯函数。
- [ ] **Step 5:** `verify` PASS；Commit（待授权）：`feat(infra): add AES/GCM api key cipher with kek versioning`。

---

### Task 10: 计量装饰器 + 每日配额

**Files:**
- Create: `modules/usage/metering/MeteredModelProvider.java`、`MeteredStreamingProvider.java`、`UsageContext.java`（common，ThreadLocal：scene/sessionId/userId/promptHash/evaluatorVersion）、`UsageRecorder.java`
- Create: `common/quota/DailyQuotaCounter.java`（端口）+ `infrastructure/cache/RedissonDailyQuotaCounter.java`
- Modify: provider 注册处（bean 装饰，不改 spi 契约）；`QaService`/`QuestionGenerationService` 调用点包 `UsageContext.bind(...)`

**Interfaces:**
- Produces: `UsageRecorder.record(String userId, String scene, UUID sessionId, String provider, String model, String purpose, UsageInfo usage)` → afterCommit 异步落 `token_usage`；`DailyQuotaCounter.incrIfBelow(String key, long limit, Duration window) -> boolean`。
- 错误码：`QUOTA_EXCEEDED(2800, "今日模型用量已达上限，明天再来或升级配额")`。

- [ ] **Step 1: 失败单测**（slice）：装饰器透传 chat 结果且 `usage!=null` 时恰好一次 record、`usage==null`（provider 未返回）时 record 记 0 而非跳过；配额超限路径**不调底层**直接抛 `2800`（熔断在调用前，省钱才是熔断）。
- [ ] **Step 2: 实现**——`MeteredModelProvider implements ModelProvider`（name() 委托、chat() 内 record 后原样返回）；流式在最后一个 chunk 聚合 usage。**记账不进事务**：`TransactionSynchronization.afterCommit` + `aiIoExecutor`，失败仅 warn（丢一帧用量不阻塞用户主流程——取舍写进 Javadoc）。
- [ ] **Step 3: ThreadLocal 泄漏防线**：`UsageContext` 提供 `try(Closeable scope = UsageContext.bind(...))` AutoCloseable；流式异步在 executor lambda **内部** bind（线程池复用线程）。测试断言 finally 后 `current()==empty`。
- [ ] **Step 4: 每日配额**：key = `quota:daily:{userId}:{yyyyMMdd}`，Redis INCR+EXPIRE 原子 Lua；`@ConfigurationProperties annona.usage.daily-token-limit`（默认值唯一出处：application.yaml `annona.usage.daily-token-limit: ${ANNONA_USAGE_DAILY_TOKEN_LIMIT:200000}`）。
- [ ] **Step 5:** `verify` PASS；Commit（待授权）：`feat(usage): meter ModelProvider calls and enforce daily quota`。

---

### Task 11: llmprovider CRUD + 连通性测试 + 成本查询端点

**Files:**
- Create: `modules/llmprovider/`（controller/service/repository/entity/dto，六件套一模块自包含）
- Create: `modules/usage/controller/UsageController.java`
- Test: `LlmProviderConfigServiceTest`（slice）、`UsageFlowIT`（docker）

**Interfaces:**
- Produces: `GET/POST/PUT/DELETE /api/llm/providers`（GET 列表只回 masked）、`POST /api/llm/providers/{id}/test`、`GET /api/usage/session/{id}` → `Result<SessionUsageView>`；错误码 `PROVIDER_NOT_FOUND(2900)`、`PROVIDER_KEY_DUPLICATE(2901, "该 Provider 的此用途已配置，请直接编辑")`、`PROVIDER_TEST_FAILED(2902, "连通性测试失败，请检查 Base URL 与 Key")`。

- [ ] **Step 1: 借鉴扫描** 🅖 `LlmProviderConfigService.java`/`LlmProviderBootstrapService.java`/`common/ai/LlmProviderRegistry.java`、🅢 `src/lib/model-pool.ts`（多 Key 轮转的取舍，批 2 不做轮转，ADR 记一句）。
- [ ] **Step 2: CRUD**：入参明文 Key → `ApiKeyCipher.encrypt`（`cpuExecutor`）→ 存三列 + masked；**响应/日志全链路断言无明文**（测试用日志 appender 捕获 + AssertJ `doesNotContain(plain)`）；PUT 不带新 Key 时密文列不动。经 `ModelProvider` 端口消费配置（`LlmProviderRegistry` 形态简化为 `LlmProviderConfigService.resolveChat(userId)`，不散 `@Value`）。
- [ ] **Step 3: `POST /{id}/test`**：1-token 最小请求（借 🅖 test 端点语义），超时 10s，失败归因 2902，不泄漏底层报错原文。
- [ ] **Step 4: `GET /api/usage/session/{id}`**：聚合 SQL（`SUM(prompt_tokens+completion_tokens) GROUP BY model`）走 `queryExecutor`；估算成本按模型单价表（`@ConfigurationProperties annona.usage.model-prices`，yaml 给默认空表=成本显示"未配置单价"）。
- [ ] **Step 5: docker 集测**：超额用户第二次 `POST /api/interview/sessions` 触发模型调用 → 拿 `2800` 且文案含"今天"；真实面试链路一次 → usage 非零。
- [ ] **Step 6:** `verify` PASS + 新模块 package-info + ArchUnit 白名单；Commit（待授权）：`feat(llmprovider): add provider config CRUD, connectivity test and usage endpoints`。

---

### Task 12: 前端面试中心 + metering ADR

**Files:**
- Create: `annona-web/src/api/interview.ts`、`src/types/interview.ts`
- Modify: `src/constants/routes.ts`、`src/pages/interview/index.tsx`（441 行，批 1 questionbank UI 所在页——新面板与题库区并列不破坏既有交互）
- Create: `docs/specs/2026-09-29-llmprovider-metering-adr.md`；Modify `docs/README.md`

- [ ] **Step 1: 类型与 API 层**：`types/interview.ts` 按 T5 契约定义 `SessionView/InterviewPlan/CreateSessionRequest/FinalizeView/SessionUsageView`；`api/interview.ts` 复用 `request.ts` 实例，函数签名 `createSession(req): Promise<SessionView>`、`answer(id, index, text)`、`finalize(id)`、`abandon(id)`、`listResumable(directionId?)`、`getUsage(sessionId)`。
- [ ] **Step 2: 页面流**（状态机 UI：`idle → resumable-prompt → answering(index/total) → submitted`）：选方向→选难度档位→选题量；**容量不足禁用选项**（复用批 1 `2604` 错误码判定，toast 文案透传）；有 RESUMABLE 会话时进页先弹"继续上次面试"；断线重进 `GET /sessions/{id}` 恢复到 `currentIndex` 主题；交卷后显示 `answeredCount/totalCount` 与"评分将在下一批开放"占位（诚实文案，不假评分）。lucide-react 图标、沿用现有设计语言。vitest：状态迁移 reducer 纯函数测试 ≥3 例（含 finalize 失败回退 answering）。
- [ ] **Step 3: 四门**：`cd annona-web; pnpm typecheck; pnpm lint; pnpm test; pnpm build` → 全 EXIT=0（产物进 server static/）。
- [ ] **Step 4: metering ADR**：宽口径取舍（rerank/tts/asr 提前建设、evaluator 现在必须——写清）、与 2026-09-25 KEK ADR 的**扩展关系**（六用途、表名 `llm_provider_config`、三列结构原样）、不进事务记账的丢帧取舍、熔断在调用前的理由、不做 Key 轮转的边界、何时重新评估（托管代持上线前必须补 `annona reencrypt`）。
- [ ] **Step 5: Commit（待授权）**：`feat(web): add interview center flow with resume and submit` 与 `docs(specs): add llmprovider metering ADR` 两个 commit（docs 分离）。

---

### Task 13: 文档同步 + 全量验收

- [ ] `docs/annona-开发计划.md`：顶部进度表 P1b 行回写；任务表 04/05/10 去 🔶。
- [ ] `docs/annona-项目结构.md`：interview/orchestrator、usage、llmprovider、common/quota、common/crypto、infra crypto/quota/cache 条目。
- [ ] `.\mvnw.cmd -B -q verify` → EXIT=0；`python scripts/ci/check-migration-inventory.py` → 0。
- [ ] **人工里程碑**：本地起服务（原生 PG/Redis 或远程 dev 栈，AGENTS §8.3）走完"开始→答 2 题→杀进程重开→续→交卷→/api/usage/session 非零"，截图贴阶段 issue（出口物）。
- [ ] 开 PR → CI（含 docker-it）全绿 → 请用户验收合并；**不擅自 push**。

---

## 验收清单（对照用户需求逐项）

| 需求 | 任务 |
|---|---|
| PR0 门禁 + 可证伪自测 | T0 |
| V9/V10/V11 + FlywayBaselineIT 登记 | T2/T8 |
| 组卷去重双判 + 端点 + SingleFlight 禁入 | T4/T5（M3 修订方案） |
| 状态机/续面/交卷幂等 + 两个证伪集测先行 | T3/T5 |
| 前端面试中心 + 截图里程碑 | T12/T13 |
| Key 五用途加密（→六）掩码/CRUD/test/计量/配额/成本端点 | T9/T10/T11 |
| 3 个 ADR + 文档回写 | T2/T12/T13 |
| 排期 | 顺序 T0→T2..T5→T12（前端，此处达成可截图里程碑）→T8..T11（计量独立后置）≈ 9–10d |
