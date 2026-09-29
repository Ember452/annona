package io.annona.modules.questionbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.questionbank.dto.CapacityResponse;
import io.annona.modules.questionbank.entity.QbQuestionEntity;
import io.annona.modules.questionbank.mapper.QbQuestionMapper;
import io.annona.modules.questionbank.model.QbFollowUp;
import io.annona.modules.questionbank.repository.QbQuestionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 题库维护与容量硬约束（P1b-03）：容量算法为纯函数全测；owner 校验与状态规则切片测。 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
class QuestionBankServiceTest {

    @Mock
    private QbQuestionRepository repository;

    @Mock
    private QbQuestionMapper mapper;

    @InjectMocks
    private QuestionBankService service;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID QUESTION = UUID.randomUUID();
    private static final UUID DIRECTION = UUID.randomUUID();

    @Nested
    @DisplayName("容量硬约束（纯函数）")
    class Capacity {

        @Test
        @DisplayName("各档位可用数 = 可用追问数 ≥ N 的题数；selectable = 可用数 ≥ 主问题数")
        void evaluatesTiers() {
            List<CapacityResponse.FollowUpOption> options =
                QuestionBankService.evaluateCapacity(List.of(3, 2, 5, 0), 2);
            // tier0：4 题全算；tier2：3 题（3/2/5）；tier3：2 题（3/5）→ 恰好 selectable
            assertThat(options).hasSize(6);
            assertThat(options.get(0).availableQuestionCount()).isEqualTo(4);
            assertThat(options.get(2).availableQuestionCount()).isEqualTo(3);
            assertThat(options.get(2).selectable()).isTrue();
            assertThat(options.get(3).availableQuestionCount()).isEqualTo(2);
            assertThat(options.get(3).selectable()).isTrue();
            assertThat(options.get(4).availableQuestionCount()).isEqualTo(1);
            assertThat(options.get(4).selectable()).isFalse();
        }

        @Test
        @DisplayName("mainQuestionCount=0 时一律不可选（无意义配置，宁拒不放）")
        void zeroMainQuestionsNeverSelectable() {
            List<CapacityResponse.FollowUpOption> options =
                QuestionBankService.evaluateCapacity(List.of(5, 5), 0);
            assertThat(options).allSatisfy(o -> assertThat(o.selectable()).isFalse());
        }

        @Test
        @DisplayName("容量不足：低档位恰好满足仍可选，高档位不可选")
        void insufficientHighTiersNotSelectable() {
            List<CapacityResponse.FollowUpOption> options =
                QuestionBankService.evaluateCapacity(List.of(2, 2, 1), 3);
            // tier0/1：可用 3 ≥ 需要 3，可选；tier2 起：可用 2 < 3，不可选
            assertThat(options.get(0).selectable()).isTrue();
            assertThat(options.get(1).selectable()).isTrue();
            assertThat(options.get(2).availableQuestionCount()).isEqualTo(2);
            assertThat(options.get(2).selectable()).isFalse();
            assertThat(options.get(5).availableQuestionCount()).isZero();
        }
    }

    @Nested
    @DisplayName("题库维护")
    class Maintenance {

        @Test
        @DisplayName("owner 校验：他人题目与不存在统一报 2603")
        void ownerScopedLookup() {
            when(repository.findById(QUESTION)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.changeStatus(USER, QUESTION, "ACTIVE"))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(ErrorCode.QB_QUESTION_NOT_FOUND.getCode()));
        }

        @Test
        @DisplayName("归档态不复活：ARCHIVED → 任何非归档状态被拒")
        void archivedNotResurrectable() {
            QbQuestionEntity archived = new QbQuestionEntity();
            archived.setId(QUESTION);
            archived.setUserId(USER);
            archived.setStatus(QbQuestionEntity.STATUS_ARCHIVED);
            when(repository.findById(QUESTION)).thenReturn(Optional.of(archived));

            assertThatThrownBy(() -> service.changeStatus(USER, QUESTION, "ACTIVE"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持恢复");
        }

        @Test
        @DisplayName("非法状态值报 1001")
        void invalidStatusRejected() {
            assertThatThrownBy(() -> service.changeStatus(USER, QUESTION, "STALE"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DRAFT/ACTIVE/ARCHIVED");
        }

        @Test
        @DisplayName("状态变更落 updated_at 并回写")
        void changeStatusWrites() {
            QbQuestionEntity draft = new QbQuestionEntity();
            draft.setId(QUESTION);
            draft.setUserId(USER);
            draft.setStatus(QbQuestionEntity.STATUS_DRAFT);
            when(repository.findById(QUESTION)).thenReturn(Optional.of(draft));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(mapper.toResponse(any())).thenReturn(null);

            service.changeStatus(USER, QUESTION, "ACTIVE");
            assertThat(draft.getStatus()).isEqualTo(QbQuestionEntity.STATUS_ACTIVE);
            assertThat(draft.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("容量编排：按 ACTIVE+难度取题并统计可用追问数（空题干追问不计）")
        void capacityOrchestration() {
            QbQuestionEntity full = new QbQuestionEntity();
            full.setFollowUps(List.of(new QbFollowUp("追问1", "答", List.of(), "标准"),
                new QbFollowUp("  ", "空题干", List.of(), null)));
            QbQuestionEntity empty = new QbQuestionEntity();
            empty.setFollowUps(List.of());
            when(repository.search(USER, DIRECTION, QbQuestionEntity.STATUS_ACTIVE, (short) 3, null))
                .thenReturn(List.of(full, empty));

            CapacityResponse response = service.capacity(USER, DIRECTION, (short) 3, 2);
            // 可用追问数：full=1、empty=0
            assertThat(response.followUpOptions().get(1).availableQuestionCount()).isEqualTo(1);
            assertThat(response.followUpOptions().get(1).selectable()).isFalse();
        }
    }
}
