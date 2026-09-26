package io.annona.shared.direction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.shared.direction.dto.CreateDirectionRequest;
import io.annona.shared.direction.dto.DirectionResponse;
import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.mapper.DirectionMapperImpl;
import io.annona.shared.direction.repository.DirectionRepository;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DirectionCommandService 的 Mockito 切片：字典写侧的业务规则——owner 命名空间、
 * 200 上限、归档即唯一删除路径、绑定单向升级。mapper 用 MapStruct 真实现，repository 全 mock。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("DirectionCommandService：字典写侧业务规则")
class DirectionCommandServiceTest {

    /** none 模式的固定 UUID 用户，拿来当测试 owner 正好。 */
    private static final String OWNER = "00000000-0000-0000-0000-000000000001";

    @Mock
    private DirectionRepository repository;

    @Mock
    private EntityManager entityManager;

    private DirectionCommandService service;

    @BeforeEach
    void setUp() {
        service = new DirectionCommandService(repository, new DirectionMapperImpl(), entityManager);
    }

    @Nested
    @DisplayName("新建（USER_CUSTOM 即时落库）")
    class Create {

        @Test
        @DisplayName("显式 key 原样落库，origin/status/owner 形状正确")
        void persistsWithExplicitKey() {
            when(repository.countByUserIdAndOriginAndStatus(
                UUID.fromString(OWNER), "USER_CUSTOM", "ACTIVE")).thenReturn(0L);
            when(repository.existsByUserIdAndKey(UUID.fromString(OWNER), "java-concurrency"))
                .thenReturn(false);
            when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

            DirectionResponse response = service.create(OWNER,
                new CreateDirectionRequest("Java 并发", "java-concurrency"));

            ArgumentCaptor<DirectionEntity> captor = ArgumentCaptor.forClass(DirectionEntity.class);
            verify(repository).saveAndFlush(captor.capture());
            DirectionEntity saved = captor.getValue();
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getKey()).isEqualTo("java-concurrency");
            assertThat(saved.getName()).isEqualTo("Java 并发");
            assertThat(saved.getOrigin()).isEqualTo("USER_CUSTOM");
            assertThat(saved.getStatus()).isEqualTo("ACTIVE");
            assertThat(saved.getUserId()).isEqualTo(UUID.fromString(OWNER));
            assertThat(response.key()).isEqualTo("java-concurrency");
            assertThat(response.kbDocId()).isNull();
        }

        @Test
        @DisplayName("同 owner 已有同名 ACTIVE 方向 → 2101（key 是随机段，name 维度判重）")
        void duplicateNameRejected() {
            when(repository.existsByUserIdAndNameAndStatus(
                UUID.fromString(OWNER), "刑法学", "ACTIVE")).thenReturn(true);

            assertThatThrownBy(() ->
                service.create(OWNER, new CreateDirectionRequest("刑法学", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(2101));
        }

        @Test
        @DisplayName("同 owner 重复 key → 2101")
        void duplicateKeyRejected() {
            when(repository.countByUserIdAndOriginAndStatus(any(), any(), any())).thenReturn(0L);
            when(repository.existsByUserIdAndKey(UUID.fromString(OWNER), "java")).thenReturn(true);

            assertThatThrownBy(() ->
                service.create(OWNER, new CreateDirectionRequest("Java", "java")))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(2101));
        }

        @Test
        @DisplayName("第 201 个自定义方向 → 2102")
        void limitReachedRejected() {
            when(repository.countByUserIdAndOriginAndStatus(
                UUID.fromString(OWNER), "USER_CUSTOM", "ACTIVE")).thenReturn(200L);

            assertThatThrownBy(() ->
                service.create(OWNER, new CreateDirectionRequest("新方向", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(2102));
        }

        @Test
        @DisplayName("显式 key 非法（大写）→ 1001 而非 500")
        void invalidExplicitKeyRejected() {
            assertThatThrownBy(() ->
                service.create(OWNER, new CreateDirectionRequest("Java", "Java")))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(1001));
        }

        @Test
        @DisplayName("名称空白或超 128 字 → 1001")
        void invalidNameRejected() {
            assertThatThrownBy(() ->
                service.create(OWNER, new CreateDirectionRequest("   ", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(1001));
            assertThatThrownBy(() -> service.create(OWNER,
                    new CreateDirectionRequest("长".repeat(129), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(1001));
        }
    }

    @Nested
    @DisplayName("归档（唯一的删除路径，物理删端点不存在）")
    class Archive {

        @Test
        @DisplayName("归档自己的方向 → status 写为 ARCHIVED")
        void archivesOwn() {
            DirectionEntity entity = activeCustomDirection();
            when(repository.findByIdAndUserId(entity.getId(), UUID.fromString(OWNER)))
                .thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.archive(OWNER, entity.getId().toString());

            assertThat(entity.getStatus()).isEqualTo("ARCHIVED");
        }

        @Test
        @DisplayName("归档内置/他人方向（owner 范围查不到）→ 2100，不泄露存在性")
        void foreignDirectionNotFound() {
            when(repository.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.archive(OWNER, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(2100));
        }

        @Test
        @DisplayName("方向 id 非法 UUID → 1001（格式错误与 kbDocId 同口径；合法 UUID 查不到才 2100）")
        void malformedIdRejected() {
            assertThatThrownBy(() -> service.archive(OWNER, "not-a-uuid"))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(1001));
        }
    }

    @Nested
    @DisplayName("绑定知识库（USER_CUSTOM → KNOWLEDGE_BASE 单向升级）")
    class BindKbDoc {

        @Test
        @DisplayName("绑定成功 → kb_doc_id 落库且 origin 升级为 KNOWLEDGE_BASE")
        void bindsAndUpgradesOrigin() {
            DirectionEntity entity = activeCustomDirection();
            when(repository.findByIdAndUserId(entity.getId(), UUID.fromString(OWNER)))
                .thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            String docId = UUID.randomUUID().toString();

            DirectionResponse response =
                service.bindKbDoc(OWNER, entity.getId().toString(), docId);

            assertThat(entity.getKbDocId()).isEqualTo(UUID.fromString(docId));
            assertThat(entity.getOrigin()).isEqualTo("KNOWLEDGE_BASE");
            assertThat(response.kbDocId()).isEqualTo(docId);
        }

        @Test
        @DisplayName("归档态方向拒绝绑定 → 1001")
        void archivedDirectionRejected() {
            DirectionEntity entity = activeCustomDirection();
            entity.setStatus("ARCHIVED");
            when(repository.findByIdAndUserId(entity.getId(), UUID.fromString(OWNER)))
                .thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.bindKbDoc(OWNER,
                    entity.getId().toString(), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(1001));
        }

        @Test
        @DisplayName("kbDocId 非法 UUID → 1001")
        void malformedDocIdRejected() {
            assertThatThrownBy(() -> service.bindKbDoc(OWNER,
                    UUID.randomUUID().toString(), "nope"))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(1001));
        }
    }

    private DirectionEntity activeCustomDirection() {
        DirectionEntity entity = new DirectionEntity();
        entity.setId(UUID.randomUUID());
        entity.setKey("custom-1a2b3c4d");
        entity.setName("刑法学");
        entity.setOrigin("USER_CUSTOM");
        entity.setStatus("ACTIVE");
        entity.setUserId(UUID.fromString(OWNER));
        return entity;
    }
}
