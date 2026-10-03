package io.annona.modules.identity.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.identity.dto.ProfileResponse;
import io.annona.modules.identity.dto.UpdateProfileRequest;
import io.annona.modules.identity.entity.AppUserEntity;
import io.annona.modules.identity.entity.UserProfileEntity;
import io.annona.modules.identity.repository.AppUserRepository;
import io.annona.modules.identity.repository.UserProfileRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 个人资料读写（P2-07，identity 属主；结构文档 §4「资料/头像 REST」归 identity）。
 *
 * <p>{@code user_profile} 与 {@code app_user} 1:1、主键即 user_id：注册时由
 * {@link AuthUserRegistrar} 建行，故本服务假定行已存在；万一缺失（历史数据）读时给默认视图、
 * 写时懒建，不额外加迁移。
 *
 * <p>取舍：nickname/bio 长度校验放应用层而非 DB CHECK——列已是 VARCHAR 硬上限，
 * 应用层只是把「超长」翻译成可读业务错误（400 段），不指望 DB 兜用户体验。
 */
@Service
public class ProfileService {

    /** 与 V1 列宽对齐：nickname VARCHAR(64) / bio VARCHAR(500)。 */
    static final int MAX_NICKNAME = 64;
    static final int MAX_BIO = 500;

    private final AppUserRepository userRepository;
    private final UserProfileRepository profileRepository;

    public ProfileService(AppUserRepository userRepository, UserProfileRepository profileRepository) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
    }

    /** 前置：用户存在且未软删（否则 SESSION_EXPIRED，与 UserQueryService 同口径）。 */
    @Transactional(readOnly = true)
    public ProfileResponse load(String userId) {
        AppUserEntity user = requireActiveUser(userId);
        UserProfileEntity profile = profileRepository.findById(user.getId()).orElse(null);
        return toResponse(user, profile);
    }

    /**
     * 更新可编辑字段（null 表示不动）。副作用：无 profile 行时懒建。
     * 返回更新后的完整视图。
     */
    @Transactional
    public ProfileResponse update(String userId, UpdateProfileRequest request) {
        AppUserEntity user = requireActiveUser(userId);
        UserProfileEntity profile = profileRepository.findById(user.getId())
            .orElseGet(() -> newProfile(user.getId()));
        if (request.nickname() != null) {
            String nickname = request.nickname().trim();
            if (nickname.length() > MAX_NICKNAME) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "昵称不得超过 " + MAX_NICKNAME + " 字符");
            }
            profile.setNickname(nickname.isEmpty() ? null : nickname);
        }
        if (request.bio() != null) {
            if (request.bio().length() > MAX_BIO) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "简介不得超过 " + MAX_BIO + " 字符");
            }
            profile.setBio(request.bio().isBlank() ? null : request.bio());
        }
        profileRepository.save(profile);
        return toResponse(user, profile);
    }

    private AppUserEntity requireActiveUser(String userId) {
        UUID id = parseUuid(userId);
        AppUserEntity user = userRepository.findById(id).orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.SESSION_EXPIRED);
        }
        return user;
    }

    private UserProfileEntity newProfile(UUID userId) {
        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(userId);
        return profile;
    }

    /** profile 为空给默认视图（timezone/theme 用实体字段初值，不查库）。 */
    private ProfileResponse toResponse(AppUserEntity user, UserProfileEntity profile) {
        if (profile == null) {
            profile = newProfile(user.getId());
        }
        return new ProfileResponse(user.getId().toString(), user.getEmail(), profile.getNickname(),
            profile.getBio(), profile.getTimezone(), profile.getThemeKey(), profile.getAvatarObjectKey());
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.SESSION_EXPIRED);
        }
    }
}
