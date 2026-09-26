package io.annona.modules.identity.service;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.session.SessionStore;
import io.annona.modules.identity.entity.AppUserEntity;
import io.annona.modules.identity.repository.AppUserRepository;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 登录编排。<b>不加 {@code @Transactional}</b>：DB 读写走各自 repository/服务的事务，
 * Redis 签发与审计投影都在事务之外（AGENTS：外部系统不入事务）。
 *
 * <p>审计投影写失败仅 warn（identity ADR §后果 §2），签发与返回不受影响。
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository userRepository;
    private final PasswordHasher hasher;
    private final LoginAttemptStore attemptStore;
    private final SessionStore sessionStore;
    private final SessionProjectionWriter projectionWriter;
    private final SessionProperties sessionProps;
    /** 用于邮箱不存在时消耗等量 scrypt 时间，抵消用户枚举的时序侧信道（identity ADR §后果 §5）。 */
    private final String dummyHash;

    public LoginService(AppUserRepository userRepository,
                        PasswordHasher hasher,
                        LoginAttemptStore attemptStore,
                        SessionStore sessionStore,
                        SessionProjectionWriter projectionWriter,
                        SessionProperties sessionProps) {
        this.userRepository = userRepository;
        this.hasher = hasher;
        this.attemptStore = attemptStore;
        this.sessionStore = sessionStore;
        this.projectionWriter = projectionWriter;
        this.sessionProps = sessionProps;
        this.dummyHash = hasher.hash("annona-dummy-" + java.util.UUID.randomUUID());
    }

    public LoginOutcome login(String rawEmail, String rawPassword, String ip, String device, String userAgent) {
        String email = Emails.normalize(rawEmail);
        if (!Emails.isValid(email)) {
            // 格式/超长在入口拒绝：不让超长 email 拼进 login_attempt.key 撞列长伪装成 500；
            // 格式信息不泄露账号存在性，与下面的等耗防护不冲突（identity ADR「后续修订」加固批）。
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
        }
        String key = email + "|" + (ip == null ? "" : ip);
        attemptStore.assertNotLocked(key);

        Optional<AppUserEntity> found = userRepository.findActiveByEmail(email);
        boolean ok;
        if (found.isPresent()) {
            ok = hasher.matches(rawPassword, found.get().getPasswordHash());
        } else {
            hasher.matches(rawPassword, dummyHash); // 不做真实校验，只消耗等量 CPU 时间
            ok = false;
        }
        if (!ok) {
            attemptStore.recordFailure(key);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        AppUserEntity user = found.get();
        attemptStore.reset(key);
        if (hasher.needsRehash(user.getPasswordAlgo())) {
            user.setPasswordHash(hasher.hash(rawPassword));
            user.setPasswordAlgo(hasher.currentAlgo());
            userRepository.save(user);
        }

        String token = newToken();
        sessionStore.create(token, user.getId().toString(), sessionProps.getTtl());
        try {
            projectionWriter.record(user.getId(), ip, device, userAgent);
        } catch (RuntimeException e) {
            log.warn("user_session 审计投影写入失败（不影响登录）: userId={}", user.getId(), e);
        }
        return new LoginOutcome(user.getId().toString(), token);
    }

    private static String newToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
