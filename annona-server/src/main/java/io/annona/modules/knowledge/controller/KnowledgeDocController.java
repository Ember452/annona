package io.annona.modules.knowledge.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.knowledge.dto.KbDocDetailResponse;
import io.annona.modules.knowledge.dto.KbDocStatusResponse;
import io.annona.modules.knowledge.dto.KbDocSummaryResponse;
import io.annona.modules.knowledge.dto.UploadResponse;
import io.annona.modules.knowledge.ingest.KnowledgeUploadService;
import io.annona.modules.knowledge.ops.KnowledgeDocLifecycleService;
import io.annona.modules.knowledge.ops.KnowledgeDocQueryService;
import io.annona.modules.knowledge.progress.KnowledgeProgressHub;
import io.annona.spi.dto.Principal;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 知识库文档端点（/api/knowledge/docs，/api/** 强制鉴权白名单之外）。
 * 错误码段 2300–2399；业务失败统一由全局异常处理器转 Result.error（controller 禁 try/catch）。
 * userId/docId 全程 String 口径（SPI Principal.id() 契约，study 同款）。
 */
@RestController
@RequestMapping("/api/knowledge/docs")
public class KnowledgeDocController {

    private final KnowledgeUploadService uploadService;
    private final KnowledgeDocQueryService queryService;
    private final KnowledgeDocLifecycleService lifecycleService;
    private final KnowledgeProgressHub progressHub;

    public KnowledgeDocController(KnowledgeUploadService uploadService,
        KnowledgeDocQueryService queryService, KnowledgeDocLifecycleService lifecycleService,
        KnowledgeProgressHub progressHub) {
        this.uploadService = uploadService;
        this.queryService = queryService;
        this.lifecycleService = lifecycleService;
        this.progressHub = progressHub;
    }

    /**
     * 上传文档（multipart：file + directionId）。重复上传（同用户同内容）返回
     * duplicate=true 且零处理消耗；上传后异步解析/向量化，进度见 SSE 端点。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<UploadResponse> upload(@CurrentPrincipal Principal principal,
        @RequestPart("file") MultipartFile file,
        @RequestParam("directionId") String directionId) throws java.io.IOException {
        return Result.success(uploadService.upload(principal.id(), file.getBytes(),
            file.getOriginalFilename(), directionId));
    }

    /** 当前用户文档列表（按上传时间倒序）。 */
    @GetMapping
    public Result<List<KbDocSummaryResponse>> list(@CurrentPrincipal Principal principal) {
        return Result.success(queryService.list(principal.id()));
    }

    /** 文档详情：摘要 + 分块预览（READY 后 chunks 非空；处理中为空列表）。 */
    @GetMapping("/{id}")
    public Result<KbDocDetailResponse> detail(@CurrentPrincipal Principal principal,
        @PathVariable String id) {
        return Result.success(queryService.detail(principal.id(), id));
    }

    /** 处理状态快照（SSE 断线时的轮询兜底，字段口径与 progress 事件一致）。 */
    @GetMapping("/{id}/status")
    public Result<KbDocStatusResponse> status(@CurrentPrincipal Principal principal,
        @PathVariable String id) {
        return Result.success(queryService.status(principal.id(), id));
    }

    /** SSE 进度流（text/event-stream，事件名 progress，信封见 ProgressEvent）。 */
    @GetMapping(value = "/{id}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@CurrentPrincipal Principal principal, @PathVariable String id) {
        queryService.status(principal.id(), id); // 订阅即校验归属（非 owner 报 2300，不发流）
        return progressHub.subscribe(java.util.UUID.fromString(id));
    }

    /** 删除文档（级联清理分块与 S3 对象）。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@CurrentPrincipal Principal principal, @PathVariable String id) {
        lifecycleService.delete(principal.id(), id);
        return Result.success(null);
    }

    /** 手动重嵌（READY/FAILED → PENDING 重新走管线；重建式，ADR §决策 7）。 */
    @PostMapping("/{id}/revectorize")
    public Result<Void> revectorize(@CurrentPrincipal Principal principal, @PathVariable String id) {
        lifecycleService.revectorize(principal.id(), id);
        return Result.success(null);
    }
}
