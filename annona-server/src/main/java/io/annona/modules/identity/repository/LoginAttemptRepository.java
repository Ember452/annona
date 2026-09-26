package io.annona.modules.identity.repository;

import io.annona.modules.identity.entity.LoginAttemptEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginAttemptRepository extends JpaRepository<LoginAttemptEntity, String> {

    /**
     * 幂等建行：并发首次失败时双方都执行也只插一行（PG 对未提交的同键插入会先等对方事务结束，
     * 再判定冲突后什么都不做，不抛约束冲突）。PG 专有语法；last_at 直接用库端 now()，
     * 新行的 fail_count=0 使滑动窗口判定不受影响，随后即被写侧的应用时钟覆盖。
     */
    @Modifying
    @Query(value = "INSERT INTO login_attempt (key, fail_count, last_at) VALUES (:key, 0, now())"
        + " ON CONFLICT (key) DO NOTHING", nativeQuery = true)
    void insertIfAbsent(@Param("key") String key);

    /** 行级写锁读取（SELECT … FOR UPDATE）：串行化同 key 的「读-改-写」，防止并发失败计数丢失更新。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LoginAttemptEntity> findByKey(String key);
}
