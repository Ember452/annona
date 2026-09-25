package io.annona.spi.fake;

import io.annona.spi.dto.Principal;
import io.annona.spi.identity.IdentityProvider;
import java.util.Optional;
import java.util.Set;

/**
 * {@link IdentityProvider} 的内存实现，模拟 {@code annona.identity.mode=none} 的
 * 单机免登录路径（identity-credential-storage-adr §决策 §4）。
 *
 * <p>任何凭据都被接受，返回固定 {@code Principal(id="local", "Local User", ["USER"])}。
 * 生产 profile 不应装配本 bean；测试与本地"零配置跑起来"是它的两个用途。
 */
public final class FakeIdentityProvider implements IdentityProvider {

    /** 与 {@code annona.identity.mode} 的取值 {@code none} 对齐。 */
    public static final String MODE = "none";

    private static final Principal LOCAL =
        new Principal("local", "Local User", Set.of("USER"));

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
