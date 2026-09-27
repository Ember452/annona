package io.annona.spi.fake;

import io.annona.spi.dto.Principal;
import io.annona.spi.identity.IdentityProvider;
import java.util.Optional;
import java.util.Set;

/**
 * {@link IdentityProvider} 的内存实现，模拟 {@code annona.identity.mode=none} 的
 * 单机免登录路径（identity-credential-storage-adr §决策 §4）。
 *
 * <p>任何凭据都被接受，返回固定 {@code Principal(id=固定UUID, "Local User", ["USER"])}。
 * id 必须与生产实现 {@code NoneIdentityProvider.LOCAL_USER_ID} 同值——业务表外键是 UUID，
 * 字面量 {@code "local"} 落不了库（P1a-02 定案；fake 若漂移，下游测试一落库就炸）。
 * 生产 profile 不应装配本 bean；测试与本地"零配置跑起来"是它的两个用途。
 */
public final class FakeIdentityProvider implements IdentityProvider {

    /** 与 {@code annona.identity.mode} 的取值 {@code none} 对齐。 */
    public static final String MODE = "none";

    /** 与真实 {@code NoneIdentityProvider.LOCAL_USER_ID} 一致（spi 不依赖 server，故字面量复制，改动须两处同步）。 */
    public static final String LOCAL_USER_ID = "00000000-0000-0000-0000-000000000001";

    private static final Principal LOCAL =
        new Principal(LOCAL_USER_ID, "Local User", Set.of("USER"));

    @Override
    public String mode() {
        return MODE;
    }

    @Override
    public Optional<Principal> authenticate(String credentialToken) {
        // 与真实实现一致：不抛，只返 empty 表示"这 token 无效"。但 fake 语义是
        // "所有 token 都视为已登录的本地用户"，包括 null / 空串。
        return Optional.of(LOCAL);
    }
}
