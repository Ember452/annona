/**
 * 中文分词端口（retrieval-hybrid-adr §决策 10）。实现（jieba-analysis）在
 * annona-infrastructure 的 search 包；knowledge 写侧与 retrieval 读侧都只注入本端口。
 *
 * <p>为什么端口在 common 而不是 spi：它的输出形状被 V5 的生成列表达式冻结
 * （换分词器 = 改 DDL），第三方无法只换一个 bean 就替换它，所以这是内部解耦
 * 而非对外扩展点（判据见 AGENTS.md §4）。
 */
package io.annona.common.search;
