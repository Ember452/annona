package io.annona.common.session;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把当前请求的已认证主体注入 controller 方法参数（由会话过滤器先行解析后填充）。
 *
 * <p>放在 {@code annona-common} 而非 {@code config.web}，是为了让 {@code modules.identity}
 * 的 controller 只依赖 common、不反向依赖装配层，消除 module 与 config.web 的包级双向耦合。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentPrincipal {
}
