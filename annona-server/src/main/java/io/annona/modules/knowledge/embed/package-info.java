/**
 * 向量化批处理（knowledge-ingestion-adr §决策 3/4）：下载 → 解析 → 分块 → 落库 →
 * 批量嵌入 → 条件推进到 READY。状态迁移全部经 KbDocRepository 条件 UPDATE。
 */
package io.annona.modules.knowledge.embed;
