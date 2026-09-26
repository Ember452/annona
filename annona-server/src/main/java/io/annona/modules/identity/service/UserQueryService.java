package io.annona.modules.identity.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.identity.dto.AuthUserResponse;
import io.annona.modules.identity.entity.AppUserEntity;
import io.annona.modules.identity.mapper.IdentityMapper;
import io.annona.modules.identity.repository.AppUserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 读取当前用户视图（供 /api/me）。令牌有效但用户已消失 → 视为会话失效。 */
@Service
public class UserQueryService {

    private final AppUserRepository userRepository;
    private final IdentityMapper mapper;

    public UserQueryService(AppUserRepository userRepository, IdentityMapper mapper) {
        this.userRepository = userRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public AuthUserResponse loadByUserId(String userId) {
        AppUserEntity user = parseUuid(userId)
            .flatMap(userRepository::findById)
            .orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.SESSION_EXPIRED);
        }
        return mapper.toResponse(user);
    }

    private static java.util.Optional<UUID> parseUuid(String s) {
        try {
            return java.util.Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
