package io.annona.config.web;

import java.util.Locale;

/**
 * SPA history 路由回退判定（阶段总结 D1）。
 *
 * <p>annona-web 用 {@code createBrowserRouter}（history 模式），要求任意<b>非资源</b>路径
 * （如 {@code /study}、{@code /interview/123}）刷新时都返回 {@code index.html} 由前端接管路由，
 * 而不是 404。但回退<b>绝不能</b>吞掉两类必须真实 404 的请求（否则破坏 D19 与可调试性）：
 * <ul>
 *   <li>API / 运维端点：{@code /api}、{@code /actuator}、{@code /v3}、{@code /swagger-ui}、{@code /ws}；</li>
 *   <li>带扩展名的静态资源请求（如 {@code /assets/missing.js}、{@code /favicon.ico}）——缺失的
 *       JS/CSS/图片必须回 404，不能被伪装成 index.html，否则浏览器把 HTML 当脚本解析、故障极难定位。</li>
 * </ul>
 *
 * <p>本类是<b>纯决策逻辑</b>（不依赖 Spring / 文件系统），故可被完整单测锁死真值表；
 * 真正的“资源是否存在”判断由 {@link SpaWebMvcConfig} 的解析器先行处理，未命中才调本判定。
 *
 * <p>借鉴说明：🅖 用 nginx {@code try_files} 在反代层解决，但 annona 静态资源打进 jar 由
 * Spring 直出、nginx 仅 {@code proxy_pass}，拓扑不同 → Spring 侧回退为 annona 独有实现。
 */
public final class SpaFallbackPolicy {

    /** 命中这些前缀的路径永不回退 index.html（保持真实 404 / 交由各自 handler）。 */
    private static final String[] RESERVED_PREFIXES = {
        "api/", "actuator/", "v3/", "swagger-ui", "swagger-resources", "webjars/", "ws/",
    };

    private SpaFallbackPolicy() {
    }

    /**
     * 判定一个「未作为静态资源命中」的请求路径是否应回退到 {@code index.html}。
     *
     * @param resourcePath Spring 解析器传入的相对路径（无前导斜杠），如 {@code study}、{@code interview/123}
     * @return {@code true} 表示这是前端路由，应返回 index.html；{@code false} 表示应保持 404
     */
    public static boolean shouldFallbackToIndex(String resourcePath) {
        if (resourcePath == null) {
            return false;
        }
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        if (path.isEmpty()) {
            // 根路径 "/" 由 Boot 的 welcome page（static/index.html）处理，不在此重复回退
            return false;
        }
        String normalized = path.toLowerCase(Locale.ROOT);
        for (String reserved : RESERVED_PREFIXES) {
            if (normalized.startsWith(reserved)) {
                return false;
            }
        }
        int slash = normalized.lastIndexOf('/');
        String lastSegment = slash < 0 ? normalized : normalized.substring(slash + 1);
        // 末段含点 → 像带扩展名的资源文件（.js/.css/.png/.ico…）→ 缺失即真 404，不回退
        return !lastSegment.contains(".");
    }
}
