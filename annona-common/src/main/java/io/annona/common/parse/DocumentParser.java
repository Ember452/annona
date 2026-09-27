package io.annona.common.parse;

import java.util.List;

/**
 * 文档解析端口（knowledge-ingestion-adr §决策 1/8）：文件字节进，结构化块 IR 出。
 *
 * <p>Tika 实现在 annona-infrastructure（infrastructure/parse），本端口只约定契约；
 * 解析器不做成 SPI 五扩展点之一（无第二实现规划，多解析器抽象已被 ADR 否决）。
 *
 * <p>约定：实现负责格式嗅探、清洗、块切分，并保证块偏移与清洗后全文一致；
 * 解析超时与 IO 失败以运行时异常上抛。事务铁律（overview §4）：本端口调用必须在
 * 数据库事务之外。
 */
public interface DocumentParser {

    /**
     * @param content  文件字节（调用方已完成大小与 MIME 白名单校验）
     * @param filename 原始文件名，供格式嗅探兜底
     * @return 按文档顺序排列的块序列；无法解析出任何内容时返回空列表（由调用方判定失败）
     */
    List<DocumentBlock> parse(byte[] content, String filename);
}
