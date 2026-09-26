package io.annona.config.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

/**
 * SPA 回退<b>解析器</b>的三分支证明（阶段总结 D1）——不依赖 Web 容器，直接对
 * {@link SpaWebMvcConfig.SpaFallbackResolver} 传真实的 {@code classpath:/static/} 位置：
 * <ol>
 *   <li>前端路由未命中真实资源 → 回退 index.html（内容取自 test classpath 固件）；</li>
 *   <li>API 路径未命中 → 返回 null（上层据此抛 NoResourceFoundException → 真实 404，守 D19）；</li>
 *   <li>带扩展名的缺失资源 → 返回 null（不能被伪装成 HTML）。</li>
 * </ol>
 * 「{@code /**} 是否被 Boot 采纳」这一层由 compose-smoke 的 {@code curl /study} 在 CI 兜底；
 * 本测试锁定的是决策与胶水逻辑本身，故 fresh clone（main 侧 static 为空）也能确定性复现。
 */
@DisplayName("SpaFallbackResolver：真实资源 / 回退 / 保持 404 三分支")
class SpaFallbackResolverTest {

    private final SpaWebMvcConfig.SpaFallbackResolver resolver = new SpaWebMvcConfig.SpaFallbackResolver();
    private final Resource location = new ClassPathResource("static/");

    @Test
    @DisplayName("单层路由 study → 回退 index.html（命中 test 固件内容）")
    void routeFallsBackToIndex() throws Exception {
        Resource result = resolver.getResource("study", location);

        assertThat(result).isNotNull();
        assertThat(content(result)).contains("ANNONA_SPA_TEST_INDEX");
    }

    @Test
    @DisplayName("多层路由 interview/123 → 回退 index.html")
    void nestedRouteFallsBackToIndex() throws Exception {
        assertThat(resolver.getResource("interview/123", location)).isNotNull();
    }

    @Test
    @DisplayName("未命中的 API 路径 → null（真实 404，不被回退吞掉）")
    void unknownApiReturnsNull() throws Exception {
        assertThat(resolver.getResource("api/definitely-not-a-route", location)).isNull();
    }

    @Test
    @DisplayName("带扩展名的缺失资源 → null（不回退成 HTML）")
    void missingAssetReturnsNull() throws Exception {
        assertThat(resolver.getResource("assets/nope-does-not-exist.js", location)).isNull();
    }

    private static String content(Resource resource) throws Exception {
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }
}
