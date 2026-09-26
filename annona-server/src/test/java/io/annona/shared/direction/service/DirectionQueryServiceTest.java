package io.annona.shared.direction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.annona.shared.direction.dto.DirectionResponse;
import io.annona.shared.direction.entity.DirectionEntity;
import io.annona.shared.direction.mapper.DirectionMapperImpl;
import io.annona.shared.direction.repository.DirectionRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("DirectionQueryService：可见方向的只读装配")
class DirectionQueryServiceTest {

    private static final String OWNER = "00000000-0000-0000-0000-000000000001";

    @Mock
    private DirectionRepository repository;

    private DirectionQueryService service;

    @BeforeEach
    void setUp() {
        service = new DirectionQueryService(repository, new DirectionMapperImpl());
    }

    @Test
    @DisplayName("内置与本人方向均映射为响应形状，UUID 转 String")
    void mapsEntitiesToResponses() {
        DirectionEntity builtin = new DirectionEntity();
        builtin.setId(UUID.randomUUID());
        builtin.setKey("java-concurrency");
        builtin.setName("Java 并发");
        builtin.setOrigin("SKILL_BUILTIN");
        builtin.setStatus("ACTIVE");
        DirectionEntity own = new DirectionEntity();
        own.setId(UUID.randomUUID());
        own.setKey("custom-1a2b3c4d");
        own.setName("刑法学");
        own.setOrigin("USER_CUSTOM");
        own.setStatus("ACTIVE");
        own.setUserId(UUID.fromString(OWNER));
        when(repository.findVisibleActive(UUID.fromString(OWNER)))
            .thenReturn(List.of(builtin, own));

        List<DirectionResponse> responses = service.listVisible(OWNER);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo(builtin.getId().toString());
        assertThat(responses.get(0).origin()).isEqualTo("SKILL_BUILTIN");
        assertThat(responses.get(0).kbDocId()).isNull();
        assertThat(responses.get(1).key()).isEqualTo("custom-1a2b3c4d");
    }

    @Test
    @DisplayName("空字典（P1b-01 前无内置方向）返回空列表")
    void emptyDictionaryReturnsEmptyList() {
        when(repository.findVisibleActive(UUID.fromString(OWNER))).thenReturn(List.of());

        assertThat(service.listVisible(OWNER)).isEmpty();
    }
}
