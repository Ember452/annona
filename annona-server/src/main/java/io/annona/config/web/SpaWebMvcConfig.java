package io.annona.config.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * SPA history 路由回退的接线（阶段总结 D1）。判定逻辑在 {@link SpaFallbackPolicy}（已纯单测锁死），
 * 本类只做「真实资源 vs 回退」这层薄胶水。
 *
 * <p>覆盖 {@code /**} 的静态解析链：
 * <ol>
 *   <li>请求能作为真实静态资源命中（{@code /}→index.html、{@code /assets/*.js}…）→ 照常返回；</li>
 *   <li>否则交给 {@link SpaFallbackPolicy}：判定为前端路由 → 返回 {@code index.html} 让 React Router 接管；
 *       判定应保持 404（API、带扩展名的缺失资源）→ 返回 {@code null}，由
 *       {@link GlobalExceptionHandler} 抛 {@code NoResourceFoundException} 给<b>真实 404</b>（守住 D19）。</li>
 * </ol>
 *
 * <p>前端未 build（fresh clone 的 {@code classpath:/static/index.html} 不存在，因 static/ 被 gitignore）时，
 * 第 2 步取 index.html 返回 {@code null} → 自然降级为 404，不报错。因此本类的端到端证据只在
 * 真正 build 了前端镜像的 CI {@code compose-smoke} 层（见 ci.yml 的 deep-link 探活）。
 *
 * <p>Boot 的 {@code WebMvcAutoConfiguration} 在检测到 {@code /**} 已被注册时会跳过其默认静态映射，
 * 故此处覆盖是生效的（该行为由 compose-smoke 的 {@code curl /study} 断言把守）。
 */
@Configuration
public class SpaWebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(new SpaFallbackResolver());
    }

    /** 真实资源优先，未命中再按 {@link SpaFallbackPolicy} 决定是否回退 index.html。 */
    static final class SpaFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = super.getResource(resourcePath, location);
            if (requested != null) {
                return requested;
            }
            if (!SpaFallbackPolicy.shouldFallbackToIndex(resourcePath)) {
                return null;
            }
            return super.getResource("index.html", location);
        }
    }
}
