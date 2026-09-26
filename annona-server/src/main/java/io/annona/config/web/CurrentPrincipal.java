package io.annona.config.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 把当前请求的已认证主体注入 controller 方法参数（依赖 {@link SessionAuthFilter} 先解析会话）。 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentPrincipal {
}
