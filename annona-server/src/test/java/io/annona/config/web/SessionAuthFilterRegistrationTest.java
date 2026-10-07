package io.annona.config.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.modules.identity.service.IdentityProperties;
import io.annona.modules.identity.service.SessionProperties;
import io.annona.spi.identity.IdentityProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * {@link SessionAuthFilter} 的注册契约回归测试（CI 假绿复盘产出）。
 *
 * <p>背景：docker 组 IT 以 {@code webEnvironment = NONE} 启动完整上下文，而本过滤器构造器
 * 依赖的 {@code handlerExceptionResolver} bean 只由 servlet 语境的 MVC 自动配置提供——
 * 曾导致所有 NONE 上下文的集测在启动期报错，且被 docker-it job 的弱断言掩盖成假绿。
 * 本测试锁定两件事：非 web 上下文<b>不注册</b>本过滤器且上下文可启动；servlet web 上下文
 * <b>注册</b>且依赖可满足。
 */
@Tag("slice")
@DisplayName("SessionAuthFilter 注册契约：仅在 servlet web 语境装配")
class SessionAuthFilterRegistrationTest {

  private final IdentityProvider identityProvider = Mockito.mock(IdentityProvider.class);
  private final HandlerExceptionResolver exceptionResolver =
      Mockito.mock(HandlerExceptionResolver.class);

  @Test
  @DisplayName("非 web 上下文：过滤器不注册，上下文可启动")
  void skippedInNonWebContext() {
    new ApplicationContextRunner()
        .withBean(IdentityProvider.class, () -> identityProvider)
        .withBean(IdentityProperties.class)
        .withBean(SessionProperties.class)
        .withBean(RequestCredentials.class)
        .withUserConfiguration(SessionAuthFilter.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(SessionAuthFilter.class);
        });
  }

  @Test
  @DisplayName("servlet web 上下文：过滤器注册且异常解析器依赖可满足")
  void registeredInServletWebContext() {
    new WebApplicationContextRunner()
        .withBean(IdentityProvider.class, () -> identityProvider)
        .withBean(IdentityProperties.class)
        .withBean(SessionProperties.class)
        .withBean(RequestCredentials.class)
        .withBean(
            "handlerExceptionResolver", HandlerExceptionResolver.class, () -> exceptionResolver)
        .withUserConfiguration(SessionAuthFilter.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(SessionAuthFilter.class);
        });
  }
}
