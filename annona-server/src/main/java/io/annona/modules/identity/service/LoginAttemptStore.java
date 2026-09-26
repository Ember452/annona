package io.annona.modules.identity.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.modules.identity.entity.LoginAttemptEntity;
import io.annona.modules.identity.repository.LoginAttemptRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录失败计数与锁定的写侧（每次操作自成一个短事务，避免同类内部调用 {@code @Transactional}）。
 */
@Service
public class LoginAttemptStore {

    private final LoginAttemptRepository repository;

    public LoginAttemptStore(LoginAttemptRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public void assertNotLocked(String key) {
        Instant now = Instant.now();
        repository.findById(key).ifPresent(a -> {
            if (LoginAttemptPolicy.isLocked(a.getLockedUntil(), now)) {
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
            }
        });
    }

    @Transactional
    public void recordFailure(String key) {
        LoginAttemptEntity entity = repository.findById(key).orElseGet(() -> {
            LoginAttemptEntity fresh = new LoginAttemptEntity();
            fresh.setKey(key);
            fresh.setFailCount(0);
            return fresh;
        });
        Instant now = Instant.now();
        LoginAttemptPolicy.Outcome outcome = LoginAttemptPolicy.recordFailure(
            LoginAttemptPolicy.effectivePriorCount(entity.getFailCount(), entity.getLastAt(), now), now);
        entity.setFailCount(outcome.failCount());
        if (outcome.lockedUntil() != null) {
            entity.setLockedUntil(outcome.lockedUntil());
        }
        entity.setLastAt(now);
        repository.save(entity);
    }

    @Transactional
    public void reset(String key) {
        repository.deleteById(key);
    }
}
