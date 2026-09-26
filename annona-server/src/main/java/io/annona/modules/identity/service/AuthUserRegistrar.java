package io.annona.modules.identity.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.identity.dto.AuthUserResponse;
import io.annona.modules.identity.entity.AppUserEntity;
import io.annona.modules.identity.entity.UserProfileEntity;
import io.annona.modules.identity.mapper.IdentityMapper;
import io.annona.modules.identity.repository.AppUserRepository;
import io.annona.modules.identity.repository.UserProfileRepository;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 注册（app_user + user_profile 原子写）。 */
@Service
public class AuthUserRegistrar {

    private static final int MIN_PASSWORD_LEN = 8;

    private final AppUserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final PasswordHasher hasher;
    private final IdentityMapper mapper;

    public AuthUserRegistrar(AppUserRepository userRepository,
                             UserProfileRepository profileRepository,
                             PasswordHasher hasher,
                             IdentityMapper mapper) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.hasher = hasher;
        this.mapper = mapper;
    }

    @Transactional
    public AuthUserResponse register(String rawEmail, String rawPassword) {
        String email = Emails.normalize(rawEmail);
        if (!Emails.isValid(email) || !StringUtils.hasText(rawPassword)
            || rawPassword.length() < MIN_PASSWORD_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式不正确或密码少于 8 位");
        }
        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        AppUserEntity user = new AppUserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPasswordHash(hasher.hash(rawPassword));
        user.setPasswordAlgo(hasher.currentAlgo());
        user.setStatus("ACTIVE");
        user.setRole("USER");
        try {
            // saveAndFlush 强制本行立即落库，并发同邮箱时唯一索引冲突能在此捕获（否则延到提交外抛，漏过 catch）
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }

        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(user.getId());
        profileRepository.save(profile);

        return mapper.toResponse(user);
    }
}
