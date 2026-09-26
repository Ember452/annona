package io.annona.modules.identity.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.identity.entity.AppUserEntity;
import io.annona.modules.identity.repository.AppUserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 改密（P1a-01 验收「改密→旧口令失效」）。以 scrypt 重算哈希并写入当前 {@code password_algo}，
 * 旧口令随即校验失败。改密后是否吊销全部活跃会话需要 user→tokens 索引，属 P1b-10（超额熔断）范畴，本批不做。
 */
@Service
public class ChangePasswordService {

    private static final int MIN_PASSWORD_LEN = 8;

    private final AppUserRepository userRepository;
    private final PasswordHasher hasher;

    public ChangePasswordService(AppUserRepository userRepository, PasswordHasher hasher) {
        this.userRepository = userRepository;
        this.hasher = hasher;
    }

    @Transactional
    public void change(String userId, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新密码少于 8 位");
        }
        AppUserEntity user = userRepository.findById(UUID.fromString(userId))
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_EXPIRED));
        if (!hasher.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "原密码不正确");
        }
        user.setPasswordHash(hasher.hash(newPassword));
        user.setPasswordAlgo(hasher.currentAlgo());
        userRepository.save(user);
    }
}
