/**
 * 文档解析端口与结构化块 IR（knowledge-ingestion-adr §决策 1）。
 *
 * <p>{@link io.annona.common.parse.DocumentParser} 是依赖倒置端口：定义在 common
 * （纯 JDK 签名），Tika 实现在 annona-infrastructure（infrastructure/parse），
 * 业务模块 modules/knowledge 只注入端口——满足 ArchUnit「modules 不 import infrastructure」。
 *
 * <p>偏移口径：IR 的 charStart/charEnd 是<b>清洗后</b>全文的下标，引用跳转（P1a-08）
 * 以它为准；清洗与偏移一致性由解析实现负责。
 */
package io.annona.common.parse;
