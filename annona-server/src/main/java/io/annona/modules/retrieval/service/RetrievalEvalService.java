package io.annona.modules.retrieval.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.retrieval.dto.EvalRunRequest;
import io.annona.modules.retrieval.entity.RetrievalEvalRunEntity;
import io.annona.modules.retrieval.repository.RetrievalEvalRunRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评测运行落库（P1a-09）。只做校验 + 投影落库，不算指标——数字由
 * {@code scripts/rag-eval/eval.py} 算，服务端再算一遍就是两处真相。
 *
 * <p>校验从严（宁可脚本红也不要表里躺着不可比的数字）：mode 必须可归一化、provider 必填、
 * 比率必须在 [0,1]、topK 与 queryCount 必须为正。全部走 {@code BAD_REQUEST(1001)}——
 * 这是内部工具的入参问题，不占业务模块错误码段。
 */
@Service
public class RetrievalEvalService {

    private static final Set<String> MODES = Set.of("BOTH", "SEMANTIC", "KEYWORD");

    private final RetrievalEvalRunRepository repository;

    public RetrievalEvalService(RetrievalEvalRunRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录一轮评测。
     *
     * @param userId 运行者（登录用户；表按 user_id 归属，与全仓业务表口径一致）
     * @param request 报告头部数字
     * @return 落库行的 id（脚本打印出来，便于与报告对账）
     * @throws BusinessException 入参不合法（1001）
     */
    @Transactional
    public String record(String userId, EvalRunRequest request) {
        require(request.querySet(), "querySet");
        require(request.label(), "label");
        require(request.backend(), "backend");
        require(request.embeddingProvider(), "embeddingProvider");
        String mode = upper(request.mode());
        if (!MODES.contains(mode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "mode 只接受 BOTH / SEMANTIC / KEYWORD，收到：" + request.mode());
        }
        if (request.topK() == null || request.topK() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "topK 必须为正整数");
        }
        if (request.queryCount() == null || request.queryCount() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "queryCount 不得为负");
        }

        // id 应用侧赋值 → save 走 merge，返回值才是受管副本；本方法不回读 DB 默认列
        RetrievalEvalRunEntity row = new RetrievalEvalRunEntity();
        row.setId(UUID.randomUUID());
        row.setUserId(uuid(userId, "userId"));
        row.setQuerySet(trimmed(request.querySet(), 128));
        row.setLabel(trimmed(request.label(), 128));
        row.setMode(mode);
        row.setBackend(trimmed(request.backend(), 32));
        row.setEmbeddingProvider(trimmed(request.embeddingProvider(), 32));
        row.setEmbeddingModel(request.embeddingModel() == null || request.embeddingModel().isBlank()
            ? null : trimmed(request.embeddingModel(), 128));
        row.setTopK(request.topK());
        row.setQueryCount(request.queryCount());
        row.setRecallAtK(ratio(request.recallAtK(), "recallAtK"));
        row.setMrrAtK(ratio(request.mrrAtK(), "mrrAtK"));
        row.setLatencyP50Ms(request.latencyP50Ms());
        row.setLatencyP95Ms(request.latencyP95Ms());
        row.setReportPath(request.reportPath() == null || request.reportPath().isBlank()
            ? null : trimmed(request.reportPath(), 500));
        return repository.save(row).getId().toString();
    }

    private static BigDecimal ratio(Double value, String field) {
        if (value == null) {
            return null;
        }
        if (value < 0.0 || value > 1.0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                field + " 必须落在 [0,1]，收到：" + value);
        }
        // 列是 NUMERIC(5,4)：不先把 Double 收成 4 位小数的话，0.33333333 这类值会在
        // 插入时被 PG 静默四舍五入，报告与表就对不上小数位
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 不能为空");
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimmed(String value, int max) {
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "字段长度超过 " + max + " 字符：" + trimmed.substring(0, Math.min(32, max)));
        }
        return trimmed;
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 不是合法 UUID");
        }
    }
}
