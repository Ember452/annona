/**
 * 检索能力（D 模块读侧，P1a-07）：语义通道 + 关键词通道 + RRF 融合。
 *
 * <p>允许依赖：{@code annona-common}（{@code Tokenizer}/{@code VectorLiterals} 端口与工具）、
 * {@code annona-spi}（{@code Retriever} 契约与 DTO）、Spring 与 JdbcTemplate。
 *
 * <p>禁止依赖：{@code modules.knowledge} 的任何类（跨模块 import 禁止，AGENTS.md §4）——
 * 本模块直接用自己的 SQL 读 {@code kb_doc}/{@code kb_doc_chunk}，因为
 * {@code embedding}/{@code tsv} 两列刻意不映射进 JPA 实体（V4/V5 列注释）。
 *
 * <p>谁在用：P1a-08 的 qa 走 {@code POST /api/retrieval/query}（或注入 {@code Retriever}），
 * P1a-09 的评测脚本走同一个端点跑三档对照组。
 */
package io.annona.modules.retrieval;
