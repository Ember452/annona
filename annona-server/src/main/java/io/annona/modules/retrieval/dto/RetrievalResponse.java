package io.annona.modules.retrieval.dto;

import java.util.List;

/**
 * 检索响应体。
 *
 * <p>命中项<b>只给坐标不给正文</b>（docId / chunkId / score）：正文、标题路径与字符偏移由
 * 消费方（qa 的引用跳转、分块预览）按 chunkId 批量回查 {@code kb_doc_chunk}。选它是因为
 * 检索 SPI 是对外发布的契约 jar，把大块文本塞进契约会让每个后端实现方都得返回同样的东西
 * （retrieval-hybrid-adr 否决表）。
 *
 * @param tookMs     端到端耗时（含查询向量化与两条通道 SQL），评测的 P50/P95 取这个口径
 * @param hits       命中列表，按 score 降序；无命中时是空列表（不是 null）
 * @param diagnostics 空命中诊断，永不为 null——"凭什么没找到"必须能回答
 */
public record RetrievalResponse(long tookMs, List<Hit> hits, Diagnostics diagnostics) {

    /**
     * @param docId   源文档 id（用于跳回文档）
     * @param chunkId 分块 id（用于回查正文与偏移）
     * @param score   归一化 RRF 融合分，[0,1]；双通道均第一 ≈1.0，仅单通道第一 ≈0.5
     */
    public record Hit(String docId, String chunkId, double score) {
    }

    /**
     * @param readyDocs        该用户 READY 的文档数（有命中时为 0，不额外查）
     * @param modelMatchedDocs 其中向量身份与当前配置一致的文档数（有命中时为 0）
     * @param reason           空命中原因；有命中时恒为 {@link RetrievalMissReason#MATCHED}
     */
    public record Diagnostics(int readyDocs, int modelMatchedDocs, RetrievalMissReason reason) {
    }
}
