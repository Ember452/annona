/**
 * 非业务的运维探针端点。
 *
 * <p>本包存在的理由：{@code /api/meta/ping} 之类的健康/契约探针<b>不属于任何业务模块</b>，
 * 但需要走 {@code @RestController} 通过 HTTP 暴露。放 {@code modules/} 会污染模块清单，
 * 放 {@code config/web/} 会混淆"MVC 装配"与"实际端点"两件事。
 *
 * <p>允许依赖：{@code io.annona.common}（Result 与 BusinessException）。
 * 禁止：依赖 {@code io.annona.modules.*} 或 {@code io.annona.infrastructure.*}。
 *
 * <p>当前内容：{@link io.annona.shared.meta.MetaController}（P0-03 验收探针）。
 * 后续 P0-12 若加 {@code /api/meta/version}、{@code /api/meta/openapi} 等也放本包。
 */
package io.annona.shared.meta;
