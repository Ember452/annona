package io.annona.modules.planner.guard;

import io.annona.spi.dto.DecisionTrace;
import java.util.List;

/**
 * 保护规则前置判定结果。{@code allowDifficultyAdjust=false} 时规则链跳过难度调整，
 * {@code notes} 里的留痕仍要落 trace（保护生效本身要可解释——P1c-04"原因写进输出"）。
 *
 * @param allowDifficultyAdjust 是否允许调整型规则改难度
 * @param notes                 保护/信息留痕（SAMPLE_GUARD、VERSION_BASELINE、SELF_REPORTED、NO_STUDY）
 */
public record GuardVerdict(boolean allowDifficultyAdjust, List<DecisionTrace> notes) {

    public GuardVerdict {
        notes = List.copyOf(notes);
    }
}
