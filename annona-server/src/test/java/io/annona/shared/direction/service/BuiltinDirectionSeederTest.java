package io.annona.shared.direction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.repository.DirectionRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** 内置方向播种的幂等口径（skill-questionbank-adr §决策 2）。 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
class BuiltinDirectionSeederTest {

    @Mock
    private DirectionRepository repository;

    @Mock
    private ObjectProvider<SkillDirectionCatalog> catalogProvider;

    private BuiltinDirectionSeeder seederWith(SkillDirectionCatalog catalog) {
        when(catalogProvider.getIfAvailable()).thenReturn(catalog);
        return new BuiltinDirectionSeeder(repository, catalogProvider);
    }

    @Test
    @DisplayName("无目录实现时静默跳过，不碰数据库")
    void skipsWithoutCatalog() {
        when(catalogProvider.getIfAvailable()).thenReturn(null);
        new BuiltinDirectionSeeder(repository, catalogProvider).run(null);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("清单全量播种：origin=SKILL_BUILTIN、user_id=NULL、status=ACTIVE")
    void seedsAllSpecs() {
        SkillDirectionCatalog catalog = () -> List.of(
            new SkillDirectionCatalog.BuiltinDirectionSpec("java-backend", "Java 后端"),
            new SkillDirectionCatalog.BuiltinDirectionSpec("star", "STAR 追问法"));
        seederWith(catalog).run(null);

        ArgumentCaptor<DirectionEntity> captor = ArgumentCaptor.forClass(DirectionEntity.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
            .allSatisfy(entity -> {
                assertThat(entity.getOrigin()).isEqualTo(DirectionEntity.ORIGIN_SKILL_BUILTIN);
                assertThat(entity.getUserId()).isNull();
                assertThat(entity.getStatus()).isEqualTo(DirectionEntity.STATUS_ACTIVE);
                assertThat(entity.getId()).isNotNull();
            });
        assertThat(captor.getAllValues()).extracting(DirectionEntity::getKey)
            .containsExactly("java-backend", "star");
    }

    @Test
    @DisplayName("已存在的 key（含归档态）跳过，不复活不回写")
    void skipsExistingKeys() {
        when(repository.existsByUserIdIsNullAndKey("java-backend")).thenReturn(true);
        SkillDirectionCatalog catalog = () -> List.of(
            new SkillDirectionCatalog.BuiltinDirectionSpec("java-backend", "Java 后端"),
            new SkillDirectionCatalog.BuiltinDirectionSpec("redis", "Redis 与缓存"));
        seederWith(catalog).run(null);

        ArgumentCaptor<DirectionEntity> captor = ArgumentCaptor.forClass(DirectionEntity.class);
        verify(repository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getKey()).isEqualTo("redis");
        assertThat(captor.getValue().getId()).isInstanceOf(UUID.class);
    }

    @Test
    @DisplayName("空清单是空操作")
    void emptyCatalogIsNoop() {
        seederWith(List::of).run(null);
        verify(repository, never()).save(any());
    }
}
