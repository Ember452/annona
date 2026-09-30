package io.annona.modules.resume.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.resume.dto.ResumeUploadResponse;
import io.annona.modules.resume.dto.ResumeView;
import io.annona.modules.resume.service.ResumeQueryService;
import io.annona.modules.resume.service.ResumeUploadService;
import io.annona.spi.dto.Principal;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历端点（/api/resume，/api/** 强制鉴权之外）。错误码段 3100–3199；
 * 业务失败由全局异常处理器转 Result.error（controller 禁 try/catch，与知识库同款）。
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private final ResumeUploadService uploadService;
    private final ResumeQueryService queryService;

    public ResumeController(ResumeUploadService uploadService, ResumeQueryService queryService) {
        this.uploadService = uploadService;
        this.queryService = queryService;
    }

    /**
     * POST /（multipart file）上传简历 → 异步 AI 分析。重复上传（同用户同内容）返回
     * duplicate=true 且零 token 消耗。错误码 3101/3102/3103/3104。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ResumeUploadResponse> upload(@CurrentPrincipal Principal principal,
                                               @RequestPart("file") MultipartFile file)
        throws java.io.IOException {
        return Result.success(uploadService.upload(principal.id(), file.getBytes(),
            file.getOriginalFilename()));
    }

    /** 当前用户简历列表（上传时间倒序，含分析状态）。 */
    @GetMapping
    public Result<List<ResumeView>> list(@CurrentPrincipal Principal principal) {
        return Result.success(queryService.list(principal.id()));
    }

    /** 简历详情（DONE 后 analysis 非空，可作面试上下文）。错误码 3100。 */
    @GetMapping("/{id}")
    public Result<ResumeView> detail(@CurrentPrincipal Principal principal, @PathVariable String id) {
        return Result.success(queryService.detail(principal.id(), id));
    }
}
