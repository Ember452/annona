package io.annona.modules.knowledge.dto;

import java.util.List;

/**
 * 文档详情 = 摘要 + 分块预览（仅 READY 后有内容；处理中分块尚未落库）。
 *
 * @param doc    摘要
 * @param chunks 分块列表（按 chunk_index 升序）
 */
public record KbDocDetailResponse(KbDocSummaryResponse doc, List<KbDocChunkView> chunks) {
}
