/**
 * 应用层中文分词实现（端口在 {@code io.annona.common.search.Tokenizer}）。
 *
 * <p>结构文档 §3 把"分词"列为本模块职责：SDK 只进 infrastructure，业务模块只注入端口。
 * 本包不做检索编排，只做"文本 → token 串"，输出形状被 V5 的生成列表达式约束。
 */
package io.annona.infrastructure.search;
