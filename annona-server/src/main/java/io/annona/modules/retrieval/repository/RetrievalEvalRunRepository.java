package io.annona.modules.retrieval.repository;

import io.annona.modules.retrieval.entity.RetrievalEvalRunEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评测运行投影表的写入入口。
 *
 * <p>刻意只有写没有读：跨轮对比由 {@code scripts/rag-eval/compare.py} 读 JSON 报告完成
 * （真相源在 git），表目前只承担"这一轮真发生过、数字是多少"的留痕。出现 UI 侧查询需求
 * 时再加派生查询方法，不预置只读接口。
 */
public interface RetrievalEvalRunRepository extends JpaRepository<RetrievalEvalRunEntity, UUID> {
}
