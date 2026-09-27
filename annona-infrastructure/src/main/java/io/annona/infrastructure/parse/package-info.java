/**
 * Tika 文档解析适配（knowledge-ingestion-adr §决策 8）：字节流 → 结构化块 IR
 * ({@link io.annona.common.parse.DocumentBlock})。清洗规则逐条移植 🅖
 * TextCleaningService；解析在专用小线程池执行并带超时（借 🅖 DocumentParseConfiguration）。
 */
package io.annona.infrastructure.parse;
