package io.annona.config.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 文档分组装配（P0-09）。
 *
 * <p>对外两个入口：
 * <ul>
 *   <li>{@code GET /v3/api-docs} —— 全量 OpenAPI JSON；</li>
 *   <li>{@code GET /v3/api-docs/meta} —— {@link #metaApi()} 定义的分组，
 *       前端 {@code pnpm gen:api} 目前只拉这一个；</li>
 *   <li>{@code GET /swagger-ui/index.html} —— 供开发者手测端点。</li>
 * </ul>
 *
 * <p>未来加"interviewApi/qaApi/studyApi"等分组时只在本类新增一个
 * {@code @Bean GroupedOpenApi}，不改业务代码（配置聚合点单一化）。
 * AGENTS.md §3.2 禁止"预留灵活性"：只落 P0-03 已经存在的 {@code /api/meta/**}，
 * 其他 endpoint 到 P1a/P1b 时按 controller 建立顺序补分组。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI annonaOpenApi(@Value("${annona.info.version:0.0.1-SNAPSHOT}") String version) {
        return new OpenAPI().info(new Info()
            .title("annona · 年轮 API")
            .description("自习室 + AI 模拟面试：共享方向字典的双功能训练平台。v1 前所有契约可能调整。")
            .version(version));
    }

    /**
     * P0-03 阶段的验收探针：{@code /api/meta/ping}、{@code /api/meta/error/business}、
     * {@code /api/meta/error/unknown}。前端 OpenAPI 类型生成的第一个消费者。
     */
    @Bean
    GroupedOpenApi metaApi() {
        return GroupedOpenApi.builder()
            .group("meta")
            .pathsToMatch("/api/meta/**")
            .build();
    }
}
