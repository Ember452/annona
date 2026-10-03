package io.annona.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.exception.BusinessException;
import io.annona.modules.identity.dto.ProfileResponse;
import io.annona.modules.identity.dto.UpdateProfileRequest;
import io.annona.modules.identity.entity.AppUserEntity;
import io.annona.modules.identity.entity.UserProfileEntity;
import io.annona.modules.identity.repository.AppUserRepository;
import io.annona.modules.identity.repository.UserProfileRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ProfileService} 切片：active 校验（软删即 SESSION_EXPIRED）、部分更新 null 不动、
 * 长度闸门、无 profile 行懒建。与 UserQueryService 同口径的用户存在性判定在此钉死。
 */
@Tag("slice")
@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileService：资料读写与懒建")
class ProfileServiceTest {

    private static final String OWNER = "00000000-0000-0000-0000-000000000001";

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private UserProfileRepository profileRepository;

    private ProfileService service;

    @BeforeEach
    void setUp() {
        service = new ProfileService(userRepository, profileRepository);
    }

    private AppUserEntity activeUser() {
        AppUserEntity user = new AppUserEntity();
        user.setId(UUID.fromString(OWNER));
        user.setEmail("a@b.com");
        user.setRole("USER");
        user.setStatus("ACTIVE");
        return user;
    }

    @Test
    @DisplayName("load：active 用户 + 已有 profile → 完整视图")
    void loadMapsProfile() {
        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(UUID.fromString(OWNER));
        profile.setNickname("小林");
        profile.setAvatarObjectKey("avatars/x/y.jpg");
        when(userRepository.findById(UUID.fromString(OWNER))).thenReturn(Optional.of(activeUser()));
        when(profileRepository.findById(UUID.fromString(OWNER))).thenReturn(Optional.of(profile));

        ProfileResponse response = service.load(OWNER);

        assertThat(response.email()).isEqualTo("a@b.com");
        assertThat(response.nickname()).isEqualTo("小林");
        assertThat(response.avatarObjectKey()).isEqualTo("avatars/x/y.jpg");
        assertThat(response.timezone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    @DisplayName("load：用户已软删 → SESSION_EXPIRED(2004)")
    void loadRejectsDeletedUser() {
        AppUserEntity user = activeUser();
        user.setDeletedAt(Instant.now());
        when(userRepository.findById(UUID.fromString(OWNER))).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.load(OWNER))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(2004));
    }

    @Test
    @DisplayName("update：昵称 trim 后入库，超 64 字符 → 1001")
    void updateValidatesNickname() {
        when(userRepository.findById(UUID.fromString(OWNER))).thenReturn(Optional.of(activeUser()));
        when(profileRepository.findById(UUID.fromString(OWNER))).thenReturn(Optional.empty());
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProfileResponse response = service.update(OWNER, new UpdateProfileRequest("  小林  ", null));
        assertThat(response.nickname()).isEqualTo("小林");

        assertThatThrownBy(() -> service.update(OWNER, new UpdateProfileRequest("x".repeat(65), null)))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(1001));
    }

    @Test
    @DisplayName("update：无 profile 行时懒建并落库")
    void updateLazyCreatesProfile() {
        when(userRepository.findById(UUID.fromString(OWNER))).thenReturn(Optional.of(activeUser()));
        when(profileRepository.findById(UUID.fromString(OWNER))).thenReturn(Optional.empty());
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(OWNER, new UpdateProfileRequest(null, "爱学习"));

        verify(profileRepository).save(any(UserProfileEntity.class));
    }
}
