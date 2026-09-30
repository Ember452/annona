# P1b 面试与评估 阶段总结

- 日期：2026-09-30 起草（阶段窗口 2026-09-29 ~ 2026-09-30）
- 对应里程碑：设计文档 §14 P1b（面试与评估）
- 状态：**待用户定稿**。出口① 的"真模型两方向各一场 + 导出"人工取证按用户决定（T-demo 不做）如实记为遗留，不粉饰。

## 1. 目标 vs 实际
三条设计主张的对齐：入口平级、可解释优先、内容不自产——本阶段交付"能面试、能评估、能解释每一分怎么来"。

| 计划项 | 状态 | 备注 |
|---|---|---|
| P1b-01 SKILL 注册表 | ✅ 批1 | 13 内置方向，热加载，缺字段报错 |
| P1b-02 出题管道 | ✅ 批1 | 异步 Stream + SSE 进度 + 恢复调度，讲义→10题带评分标准 |
| P1b-03 容量校验 | ✅ 批1 | 追问数硬约束，前端禁用 + 后端兜底 |
| P1b-04 组卷去重 | ✅ 批2 | 向量+关键词双判，golden 快照 |
| P1b-05 状态机/续面/交卷 | ✅ 批2 | fencing 条件 UPDATE 唯一守门，杀进程可续 |
| P1b-06 评估核心 | ✅ 批3 | 分批评估 + 二次汇总 + 降级留原文（`fallback_used`）|
| P1b-07 可比性 | ✅ 批3 | 难度加权总分 + 四留痕 + 趋势断开（golden + 行为规格）|
| P1b-08 简历 | ✅ 批3 | 上传→Tika→异步 AI 分析→hash 去重，报告可作面试上下文 |
| P1b-09 PDF 导出 | ✅ 批3 | iText 8 + 内置 CJK（无字体二进制）；含决策理由节 |
| P1b-10 计量/Key | ✅ 批2+批3 | BYOK 六用途 + 装饰器计量；批3 补 qa 流式/embed 计量债（TD-02/03/04）|

## 2. 本阶段产出的能力（用户视角）
- 选方向→开始面试→逐题作答（含追问）→杀进程可续→交卷；交卷幂等（重复不双份）。
- 交卷后异步评估：逐题分 + 难度加权总分 + 整场汇总 + 可解释报告（每分"凭什么"、降级题保留原文标注不计分）。
- 报告页轮询出分、SVG 雷达可视化；可一键导出 PDF（中文不乱码）。
- 简历上传→AI 结构化分析（画像/亮点/关注点/技能），可作后续面试上下文。
- 全程 token 成本可查（面试/出题/评估/问答/向量化/简历场景均入账），provider=通道、model=模型分列，BYOK 六用途加密掩码。

## 3. 验收证据
本机（无 Docker）：`.\mvnw.cmd -B -q verify` EXIT=0；三道 pre-commit 门禁（`check-migration-inventory` 14 迁移 25 表 / `check-modifying-callers` 31 @Modifying 全覆盖 / `check-test-config-shadowing`）全 0；前端四门绿（typecheck/lint/test 53/build）。
CI（docker-it 真 PG+Redis，origin/main `e9771ca`）六 job 全绿：unit+ArchUnit / **docker-it（`InterviewSessionFlowIT`、`EvaluationFlowIT` 真库幂等+状态机、`QaFlowIT` 流式计量、`MigrationShapeIT` V12/V13/V14 形状、`AllGatesOffContextIT` 含评估+简历门控）** / compose-smoke / frontend / gitleaks / gate。
ADR：evaluation-pipeline-adr、pdf-export-itext-adr、metering-adr 批3修订。批收口：P1b-批2、P1b-批3 小结。

## 4. 与原设计的偏离（均已回写 ADR/计划）
- 评估表迁移编号 V12→V13（V12 被 scene CHECK 扩展占用）；evaluator Key 走 env 通道（非 BYOK 消费，`llm_provider_config` 无运行期消费路径）。
- qa/embed 计量挂点 = 执行线程 bind + `common.UsageLedger` 端口显式记账（装饰器只认同步 chat）；PDF 走按需同步 + 内置字体（非计划原案的异步+ObjectStorage+外部署名），偏离理由与重评触发写入各自 ADR。
- 意外收获：@Modifying 机检首跑即抓出恢复调度器 `resetForRetry` 缺事务真雷（"CI 炸不到、静默丢账型"）。
- CI 两红两修：EvaluationFlowIT 类级 @Transactional 夹具 FK 跨连接不可见 → 改手工清理；resume prompt 资源漏 git add → 补提交。

## 5. 已知缺陷与技术债
| 项 | 影响 | 何时处理 |
|---|---|---|
| **出口① 真模型端到端取证未做**（两方向各一场 + 导出，人工） | 出口①只有 CI 自动化证据 + 本机单测，缺真模型人证 | 用户执行 T-demo（本批经用户决定跳过 → 记为遗留，非缺陷）|
| 评估 retry 预算占位（`StructuredOutputProperties.maxAttempts`） | 未按实测 JSON 服从率调 | T-demo 出服从率后一键替换 |
| TD-12 配置单源化（MAX_RETRY×4、调度器阈值、require-kek 默认） | 无功能影响，一致性债 | 独立低危批（非 P1b 出口，见 §6）|
| 检索查询向量不记账 | 如实声明（无会话宿主） | 托管成本对账议题 |
| 简历"→面试上下文"接线未做 | 分析已入库可查，但未自动喂给出题 | P1c/P2 需要时接 |

## 6. 遗留进入下一阶段（P1c）
- T-demo 真模型取证：**必须在 P1c 开工前补吗？建议是**——它是出口①的人证，也是 retry 预算与 rubric 成段率的首次真模型数据（P1a 已因"天花板饱和"吃过评测集教训，评估链 prompt 质量同样需实测背书）。
- TD-12：否（不挡 P1c）。

## 7. P1c 入口条件（可验证门槛）
1. 批 3/批 4 已并入 main 且 CI 保持绿（✅ 已满足，origin/main e9771ca 六 job 绿）。
2. T-demo 真模型走过一遍，评估 prompt 的 JSON 服从率有实测数（→ 回填 retry 预算与 ADR）。
3. shared.signal（学习信号）能按方向聚合读到面试评估分（planner 的输入侧，依赖本阶段 `interview_report`）。
4. 出口① 的"两方向各一场 + 导出"人工取证入 #17。

## 8. 借鉴使用记录
上游 🅖 interview-guide（出题/会话/评估/简历/PdfExport）与 🅜 MockPilot（finalize 幂等/状态机）为现成行为规格；🅢 summer-checkin（token 用量表）。均本人自有项目，搬运免许可声明、按 annona 改造（详见各 feat commit body 四行借鉴说明与借鉴地图）。本阶段新增的 annona-only 硬需求：evaluator_version/prompt_hash 四留痕 + 难度加权 + 降级保留原文 + 可比性断开（上游无，可解释主张要求）。
