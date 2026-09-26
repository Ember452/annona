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

/** 建号（app_user + user_profile 原子写）：用户自注册，以及 platform 模式的外部身份 JIT 建号。 */
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
        try {
            return mapper.toResponse(insertActiveUser(UUID.randomUUID(), email, hasher.hash(rawPassword)));
        } catch (DataIntegrityViolationException e) {
            // saveAndFlush 让唯一索引冲突在此抛出（否则延到提交外抛，漏过 catch）
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
    }

    /**
     * platform 模式的 JIT 建号：受信外部身份（邮箱）首次出现时创建本地账号。
     *
     * <p>口令写的是<b>随机值</b>——{@code password_hash} 非空约束且 v1 无「免密账号」概念，
     * 而随机口令使该账号无法用口令登录（单机/托管都由外部身份源决定它是谁）。
     *
     * <p>调用方保证 {@code email} 已归一化且格式合法（见 {@link Emails}）。并发首访时唯一索引会
     * 抛出 {@link DataIntegrityViolationException}：本方法<b>不吞</b>它，由调用方（非事务上下文）
     * 回读既有行——若在此处捕获再查库，同一事务已被标记回滚，查询必然失败。
     */
    @Transactional
    public AuthUserResponse provisionExternal(String rawEmail) {
        String email = Emails.normalize(rawEmail);
        return userRepository.findActiveByEmail(email)
            .map(mapper::toResponse)
            .orElseGet(() -> mapper.toResponse(
                insertActiveUser(UUID.randomUUID(), email, hasher.hash(randomSecret()))));
    }

    /**
     * 单机（none）模式的启动 bootstrap：确保固定主键的本地用户存在。<b>幂等</b>——已存在即返回，
     * 重复启动不报错，也不会覆盖既有资料。
     *
     * <p>口令同样是随机值（{@link #provisionExternal} 的理由），该账号无法用口令登录：单机模式没有
     * 登录入口，身份由 {@code NoneIdentityProvider} 直接给定。
     *
     * <p>放启动期而非首次请求惰性创建：启动即就绪，首个 {@code /api/me} 不会因建号竞态失败；
     * 建号失败也会在启动日志里暴露，而不是变成一次用户可见的 500。
     */
    @Transactional
    public void ensureLocalBootstrap(UUID id, String rawEmail) {
        if (userRepository.existsById(id)) {
            return;
        }
        insertActiveUser(id, Emails.normalize(rawEmail), hasher.hash(randomSecret()));
    }

    /** 建号唯一入口：注册、JIT 建号与单机 bootstrap 共用，避免多处落库逻辑漂移。 */
    private AppUserEntity insertActiveUser(UUID id, String email, String passwordHash) {
        AppUserEntity user = new AppUserEntity();
        user.setId(id);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setPasswordAlgo(hasher.currentAlgo());
        user.setStatus("ACTIVE");
        user.setRole("USER");
        userRepository.saveAndFlush(user);

        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(user.getId());
        profileRepository.save(profile);
        return user;
    }

    /** 随机口令（两枚 UUIDv4 拼接，244 位随机）；只进哈希、永不外泄。 */
    private static String randomSecret() {
        return UUID.randomUUID() + "-" + UUID.randomUUID();
    }
}