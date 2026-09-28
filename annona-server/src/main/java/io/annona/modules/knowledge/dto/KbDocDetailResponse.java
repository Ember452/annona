package io.annona.modules.knowledge.dto;

import java.util.List;

/**
 * 文档详情 = 摘要 + 分块预览（仅 READY 后有内容；处理中分块尚未落库）。
 *
 * @param doc                   摘要
 * @param currentAnalyzerVersion 服务当前的分块算法版本（{@code Chunker.VERSION}）；与
 *                              {@code doc.analyzerVersion} 不一致说明该文档是旧算法切的，
 *                              前端据此提示重建
 * @param chunks                分块列表（按 chunk_index 升序）
 */
public record KbDocDetailResponse(KbDocSummaryResponse doc, String currentAnalyzerVersion,
                                  List<KbDocChunkView> chunks) {
}
