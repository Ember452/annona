package io.annona.config.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册自定义 MVC 参数解析器（当前仅 {@link CurrentPrincipalArgumentResolver}）。 */
@Configuration
public class SessionWebConfig implements WebMvcConfigurer {

    private final CurrentPrincipalArgumentResolver currentPrincipalResolver;

    public SessionWebConfig(CurrentPrincipalArgumentResolver currentPrincipalResolver) {
        this.currentPrincipalResolver = currentPrincipalResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentPrincipalResolver);
    }
}
