package io.annona.spi.identity;

import io.annona.spi.dto.Principal;
import java.util.Optional;

/**
 * 身份源扩展点。业务代码只依赖本接口，具体实现由 {@code modules/identity/provider} 提供：
 * <ul>
 *   <li>{@code local}——本地账号：凭据为会话 Cookie（P1a-01 落地）</li>
 *   <li>{@code platform}——接入外部统一登录：凭据为受信反向代理请求头，首次访问 JIT 建号</li>
 *   <li>{@code none}——单机模式，免登录直达首页（固定 bootstrap 用户）</li>
 * </ul>
 * 三者由 {@code annona.identity.mode} 经 {@code @ConditionalOnProperty} 择一装配。
 *
 * <p>注册、会话、密码重置等写侧动作留在 {@code modules/identity}；
 * SPI 只承担<b>读侧</b>——"给我一个凭据，告诉你这是谁"。
 *
 * <p>{@link Principal} 的 {@code displayName}/{@code roles} 是身份源侧的 <b>best-effort</b>：
 * 权威的用户视图由消费方从本地库补齐（如 {@code CurrentPrincipalArgumentResolver}），
 * 实现不必为填充它们而查询数据库。
 */
public interface IdentityProvider {

    /** 供 {@code @ConditionalOnProperty annona.identity.mode} 选择实现，取值 {@code local / platform / none}。 */
    String mode();

    /**
     * 校验凭据（如会话 token / JWT）并返回主体；失败返回 {@link Optional#empty()}，
     * 禁止抛异常给上层。
     */
    Optional<Principal> authenticate(String credentialToken);
}
