package io.annona.spi.identity;

import io.annona.spi.dto.Principal;
import java.util.Optional;

/**
 * 身份源扩展点。业务代码只依赖本接口，具体实现由 {@code annona-infrastructure} 提供：
 * <ul>
 *   <li>{@code local}——本地账号（P1a-01 落地）</li>
 *   <li>{@code platform}——接入外部统一登录（如学校 SSO / 企业 IdP）</li>
 *   <li>{@code none}——单机模式，免登录直达首页（bootstrap {@code id=local}）</li>
 * </ul>
 *
 * <p>注册、会话、密码重置等写侧动作留在 {@code modules/identity}；
 * SPI 只承担<b>读侧</b>——"给我一个凭据，告诉你这是谁"。
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
