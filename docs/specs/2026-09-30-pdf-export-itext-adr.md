# ADR: PDF 导出用 iText 8 + 内置 CJK 字体、按需同步生成

- 日期：2026-09-30
- 状态：Accepted
- 相关：[evaluation-pipeline-adr](./2026-09-30-evaluation-pipeline-adr.md)（被导出的报告数据模型）、
  [批 3 计划](../plans/P1B_BATCH3_EVALUATION_PLAN.md) T6、model-api-key/KEK（许可与密钥无关，仅同仓）

## 背景

出口①要求"完成一场面试**并导出报告**"。评估报告数据已落 `interview_report`/`interview_evaluation`，
需要一个把它渲染成可下载 PDF（含中文）的通道。纠结点：①用哪个 PDF 库（引入即新依赖，AGENTS §0.6
要求先论证）；②中文字体怎么来（本项目要开源发布，往仓里塞第三方字体二进制有许可风险——计划原把
"朱雀仿宋许可核查"挂在 T-demo，而 T-demo 本轮不做）；③报告体量与并发决定同步还是异步+对象存储。
约束：项目已是 AGPL-3.0；单用户自部署为主，一场面试 ≤ 数十题。

## 决策

1. **库 = iText 8（`com.itextpdf:kernel + layout + font-asian`，8.0.5）**。版本上收根 pom
   `dependencyManagement`；依赖只加在 `annona-infrastructure`（SDK 只在 infra，AGENTS §4）。
   `itext-core` 是 pom 聚合，Maven 侧直接声明所需模块而非聚合包。
2. **中文用 font-asian 的内置 Adobe CJK 字体**（`STSong-Light` + `UniGB-UCS2-H`），
   **非嵌入**（`PREFER_NOT_EMBEDDED`）：PDF 不内嵌字形、**仓里不放任何字体二进制文件**，
   彻底绕开"朱雀仿宋/Noto 入仓许可核查"——这正是 T-demo 被跳过时仍能闭合 C9 的关键。
   依赖阅读器自带 Adobe-GB1 字体集（标准 CJK 可交换 PDF 的通行做法）。
3. **传输 = 按需同步生成、端点直返字节**（`GET /api/evaluation/sessions/{id}/report.pdf` →
   `application/pdf`），不落对象存储、不走 Redis Stream。业务模块经 `common.export.ReportPdfRenderer`
   端口拿字节，不 import iText。
4. **报告含"决策理由"节**（综合分怎么加权、降级题为何不计入、评分模型/版本留痕）——对齐项目
   "可解释优先于准确"主张，PDF 不只是分数快照。

## 否决的备选

| 备选 | 否决原因 |
|---|---|
| 嵌入朱雀仿宋 `.ttf`（🅖 有该文件） | 其许可未核（本属 T-demo 一步，跳过）；开源仓塞来历不明字体的合规风险 > 便利 |
| 引入 Noto Sans CJK 字体文件 | 许可干净（OFL）但 ~15MB 二进制入仓，且要下载；font-asian 内置字体已达"不乱码"目标，无需引文件 |
| OpenPDF（LGPL）替代 iText | 许可更宽松，但 API 停在 iText 4 代、中文支持要另配字体/编码，借鉴地图 P1b-09 与 interview-guide 均已在 iText 上验证；AGPL 与本项目同为 copyleft，无额外约束 |
| 异步导出 + ObjectStorage（计划原案） | 单用户、报告字节小（数十题 ~数十 KB）、生成纯 CPU 毫秒级；异步 + 存储引入任务态/存储配置/URL 失效等失败面，收益不抵。重评触发见下 |
| HTML→PDF（headless 渲染） | 拖入浏览器/渲染引擎依赖，远重于模板式生成 |

## 后果与约束

- iText AGPL 与项目 AGPL-3.0 同族，copyleft 叠加无冲突；**若将来出现闭源分发分支，iText 需商业授权**，届时换 OpenPDF 另议。
- 新增模型出口/端口须回 §4 端口判据登记——本端口放 common（无外部实现方需求，仅内部解耦），不放 spi。
- 基础设施 `ItexPdfRenderer` 有纯 JVM 单测（`%PDF` 头 + `%%EOF` + 含中文不抛），本机 `verify` 直跑，不依赖 Docker。

## 何时重新评估

- 出现"报告 PDF 需持久归档 / 批量导出 / 邮件投递"时：改异步 + ObjectStorage（计划原案），并把端点从"直返字节"改为"返回下载 URL"。
- 单份报告体积或并发大到同步生成拖慢 HTTP 线程时：移 aiIoExecutor + 任务流。
- 需要嵌入自定义品牌字体（而非标准 CJK）时：届时补一条已核查许可的字体入仓决策。
