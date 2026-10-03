package io.annona.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.common.storage.ObjectStorage;
import io.annona.modules.identity.entity.AvatarChangeEntity;
import io.annona.modules.identity.entity.UserProfileEntity;
import io.annona.modules.identity.repository.AvatarChangeRepository;
import io.annona.modules.identity.repository.UserProfileRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link AvatarService} 切片：上传闸门（大小/类型/存储未配置）、代理写入 + 历史记旧头像、
 * DB 失败补偿删孤儿对象、回滚交换语义。真对象存储与端到端回读由 docker 组 IT 覆盖。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("AvatarService：上传/历史/回滚")
class AvatarServiceTest {

    private static final String OWNER = "00000000-0000-0000-0000-000000000001";
    private static final byte[] IMAGE = new byte[]{1, 2, 3};

    @Mock
    private UserProfileRepository profileRepository;

    @Mock
    private AvatarChangeRepository historyRepository;

    @Mock
    private ObjectStorage storage;

    @Mock
    private PlatformTransactionManager transactionManager;

    private AvatarService service;

    @BeforeEach
    void setUp() {
        service = new AvatarService(profileRepository, historyRepository,
            Optional.of(storage), transactionManager);
    }

    private UserProfileEntity profileWith(String key) {
        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(UUID.fromString(OWNER));
        profile.setAvatarObjectKey(key);
        return profile;
    }

    @Nested
    @DisplayName("上传闸门")
    class Gate {

        @Test
        @DisplayName("空内容 → 2006")
        void rejectsEmpty() {
            assertThatThrownBy(() -> service.upload(OWNER, new byte[0], "a.jpg"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(2006));
        }

        @Test
        @DisplayName("超过 5MB → 2005，不触存储")
        void rejectsTooLarge() {
            byte[] big = new byte[(int) AvatarService.MAX_AVATAR_SIZE + 1];
            assertThatThrownBy(() -> service.upload(OWNER, big, "a.jpg"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(2005));
            verify(storage, never()).put(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("非图片扩展名（gif）→ 2006，不触存储")
        void rejectsBadExtension() {
            assertThatThrownBy(() -> service.upload(OWNER, IMAGE, "a.gif"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(2006));
            verify(storage, never()).put(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("对象存储未装配 → 2007")
        void rejectsWhenStorageAbsent() {
            AvatarService noStorage = new AvatarService(profileRepository, historyRepository,
                Optional.empty(), transactionManager);
            assertThatThrownBy(() -> noStorage.upload(OWNER, IMAGE, "a.png"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(2007));
        }
    }

    @Nested
    @DisplayName("上传落库与补偿")
    class Upload {

        @Test
        @DisplayName("有旧头像 → 旧 key 记为历史，profile 指向新 key")
        void recordsPreviousAsHistory() {
            when(profileRepository.findById(UUID.fromString(OWNER)))
                .thenReturn(Optional.of(profileWith("avatars/old.jpg")));
            when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(historyRepository.findByUserIdOrderByCreatedAtDesc(UUID.fromString(OWNER)))
                .thenReturn(List.of());

            String previous = service.upload(OWNER, IMAGE, "a.png");

            assertThat(previous).isEqualTo("avatars/old.jpg");
            verify(historyRepository).save(any(AvatarChangeEntity.class));
            verify(storage).put(anyString(), any(), org.mockito.ArgumentMatchers.eq("image/png"));
        }

        @Test
        @DisplayName("首次设置（无旧头像）→ 不记历史")
        void firstAvatarNoHistory() {
            when(profileRepository.findById(UUID.fromString(OWNER)))
                .thenReturn(Optional.of(profileWith(null)));
            when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(historyRepository.findByUserIdOrderByCreatedAtDesc(UUID.fromString(OWNER)))
                .thenReturn(List.of());

            String previous = service.upload(OWNER, IMAGE, "a.jpg");

            assertThat(previous).isNull();
            verify(historyRepository, never()).save(any());
        }

        @Test
        @DisplayName("DB 写入失败 → 删除已上传对象，不留孤儿")
        void compensatesOrphanObjectOnDbFailure() {
            when(profileRepository.findById(UUID.fromString(OWNER)))
                .thenReturn(Optional.of(profileWith(null)));
            doThrow(new RuntimeException("db down")).when(profileRepository).save(any());

            assertThatThrownBy(() -> service.upload(OWNER, IMAGE, "a.jpg"))
                .isInstanceOf(RuntimeException.class);
            verify(storage).delete(anyString());
        }
    }

    @Nested
    @DisplayName("回滚")
    class Rollback {

        @Test
        @DisplayName("有历史 → 当前与最近历史交换")
        void swapsCurrentWithHistory() {
            AvatarChangeEntity history = new AvatarChangeEntity();
            history.setId(UUID.randomUUID());
            history.setUserId(UUID.fromString(OWNER));
            history.setObjectKey("avatars/prev.jpg");
            when(historyRepository.findFirstByUserIdOrderByCreatedAtDesc(UUID.fromString(OWNER)))
                .thenReturn(Optional.of(history));
            when(profileRepository.findById(UUID.fromString(OWNER)))
                .thenReturn(Optional.of(profileWith("avatars/cur.jpg")));
            when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            String restored = service.rollback(OWNER);

            assertThat(restored).isEqualTo("avatars/prev.jpg");
            assertThat(history.getObjectKey()).isEqualTo("avatars/cur.jpg");
        }

        @Test
        @DisplayName("无历史 → 2008")
        void noHistoryRejects() {
            when(historyRepository.findFirstByUserIdOrderByCreatedAtDesc(UUID.fromString(OWNER)))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rollback(OWNER))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(2008));
        }
    }
}
