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
        // 并发安全：先幂等建行（并发首次失败不撞主键），再行锁读取后做读-改-写；
        // 同 key 的失败在行锁上串行化，计数不丢。此前的「findById→累加→save」丢失更新
        // 会让并发暴破拖慢甚至绕过 10 次锁定阈值（identity ADR「后续修订」加固批）。
        Instant now = Instant.now();
        repository.insertIfAbsent(key);
        LoginAttemptEntity entity = repository.findByKey(key).orElseGet(() -> {
            // 极端窗口：建行后到锁定读之间被并发 reset 删除 → 按全新行重新累加
            LoginAttemptEntity fresh = new LoginAttemptEntity();
            fresh.setKey(key);
            fresh.setFailCount(0);
            return fresh;
        });
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
