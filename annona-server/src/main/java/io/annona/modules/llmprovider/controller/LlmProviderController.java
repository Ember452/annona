package io.annona.modules.llmprovider.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.llmprovider.dto.ProviderTestResponse;
import io.annona.modules.llmprovider.dto.ProviderView;
import io.annona.modules.llmprovider.dto.SaveProviderRequest;
import io.annona.modules.llmprovider.service.LlmProviderService;
import io.annona.spi.dto.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider 配置端点（P1b-10）：BYOK 的增删改查 + 连通性测试。
 * 鉴权同 /api/** 全局过滤器；一切响应只含掩码（结构上无明文字段可回）。
 *
 * <p>错误码：1001 参数不合法 / 2900 配置不存在 / 2901 同用途已配置 / 2902 连通性失败。
 * 本批配置的<b>消费</b>（用户级 Key 路由业务调用）属托管模式议题，未接入——
 * 见 llmprovider-metering-adr §范围。
 */
@RestController
@RequestMapping("/api/llm/providers")
public class LlmProviderController {

    private final LlmProviderService service;

    public LlmProviderController(LlmProviderService service) {
        this.service = service;
    }

    /** GET /——当前用户全部配置（掩码视图）。 */
    @GetMapping
    public Result<List<ProviderView>> list(@CurrentPrincipal Principal principal) {
        return Result.success(service.list(UUID.fromString(principal.id())));
    }

    /** POST /——新建配置（明文 Key 只在此请求体内出现，落库即密文化）。 */
    @PostMapping
    public Result<ProviderView> create(@CurrentPrincipal Principal principal,
                                       @RequestBody SaveProviderRequest request) {
        return Result.success(service.create(UUID.fromString(principal.id()), request));
    }

    /** PUT /{id}——编辑；apiKey 留空 = 保留原密文。 */
    @PutMapping("/{id}")
    public Result<ProviderView> update(@CurrentPrincipal Principal principal, @PathVariable UUID id,
                                       @RequestBody SaveProviderRequest request) {
        return Result.success(service.update(UUID.fromString(principal.id()), id, request));
    }

    /** DELETE /{id}——删除即销毁密文（Key 撤销语义）。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@CurrentPrincipal Principal principal, @PathVariable UUID id) {
        service.delete(UUID.fromString(principal.id()), id);
        return Result.success(null);
    }

    /** POST /{id}/test——1-token 探测；失败经 2902 回可懂文案。 */
    @PostMapping("/{id}/test")
    public Result<ProviderTestResponse> test(@CurrentPrincipal Principal principal,
                                             @PathVariable UUID id) {
        return Result.success(service.testConnection(UUID.fromString(principal.id()), id));
    }
}
