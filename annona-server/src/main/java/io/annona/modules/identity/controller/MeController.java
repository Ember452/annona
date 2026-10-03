package io.annona.modules.identity.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.identity.dto.AvatarUploadResponse;
import io.annona.modules.identity.dto.ProfileResponse;
import io.annona.modules.identity.dto.UpdateProfileRequest;
import io.annona.modules.identity.service.AvatarService;
import io.annona.modules.identity.service.ProfileService;
import io.annona.spi.dto.Principal;
import java.io.IOException;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 当前用户与其资料/头像（P2-07）。无有效会话时参数解析器抛 UNAUTHORIZED（HTTP 200 + Result.error）。
 *
 * <p>路由：{@code GET /api/me}（Principal）· {@code GET/PATCH /api/me/profile}（资料）·
 * {@code POST /api/me/avatar}（multipart 上传）· {@code GET /api/me/avatar/history}（历史 key）·
 * {@code POST /api/me/avatar/rollback}（回滚，当前↔上一张交换）· {@code GET /api/me/avatar}（代理回读字节）。
 * 头像经服务端代理存取（ObjectStorage），presign 直传随数据导出方案另议（见 P2-07 裁决）。
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final ProfileService profileService;
    private final AvatarService avatarService;

    public MeController(ProfileService profileService, AvatarService avatarService) {
        this.profileService = profileService;
        this.avatarService = avatarService;
    }

    @GetMapping
    public Result<Principal> me(@CurrentPrincipal Principal principal) {
        return Result.success(principal);
    }

    /** 资料视图（昵称/简介/头像 key 等）。 */
    @GetMapping("/profile")
    public Result<ProfileResponse> profile(@CurrentPrincipal Principal principal) {
        return Result.success(profileService.load(principal.id()));
    }

    /** 部分更新资料：null 字段不动；头像不走本端点。 */
    @PatchMapping("/profile")
    public Result<ProfileResponse> updateProfile(@CurrentPrincipal Principal principal,
                                                 @RequestBody UpdateProfileRequest request) {
        return Result.success(profileService.update(principal.id(), request));
    }

    /** 上传新头像（multipart，≤5MB，jpg/png/webp）；被替换的旧头像进历史。 */
    @PostMapping("/avatar")
    public Result<AvatarUploadResponse> uploadAvatar(@CurrentPrincipal Principal principal,
                                                     @RequestParam("file") MultipartFile file)
        throws IOException {
        String previousKey = avatarService.upload(principal.id(), file.getBytes(), file.getOriginalFilename());
        return Result.success(new AvatarUploadResponse(true, previousKey));
    }

    /** 历史头像 key 列表（最近优先，含回滚窗口内的条目）。 */
    @GetMapping("/avatar/history")
    public Result<List<String>> avatarHistory(@CurrentPrincipal Principal principal) {
        return Result.success(avatarService.history(principal.id()));
    }

    /** 回滚：当前头像与最近历史交换；无历史 → 2008。 */
    @PostMapping("/avatar/rollback")
    public Result<AvatarUploadResponse> rollbackAvatar(@CurrentPrincipal Principal principal) {
        String restoredKey = avatarService.rollback(principal.id());
        return Result.success(new AvatarUploadResponse(true, restoredKey));
    }

    /**
     * 代理回读当前用户头像字节。presign 未接入时的展示替代：
     * 浏览器拿不到对象直链，就经本端点取字节流（{@code Cache-Control: private} 仅本会话缓存）。
     * 无头像或未配置存储 → 404（前端据此回退占位）。
     */
    @GetMapping(value = "/avatar", produces = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE,
        "image/webp"})
    public ResponseEntity<byte[]> avatarImage(@CurrentPrincipal Principal principal) {
        ProfileResponse profile = profileService.load(principal.id());
        String key = profile.avatarObjectKey();
        byte[] bytes = avatarService.fetch(key);
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = key.endsWith(".png") ? MediaType.IMAGE_PNG
            : key.endsWith(".webp") ? MediaType.parseMediaType("image/webp") : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type)
            .cacheControl(CacheControl.noCache())
            .body(bytes);
    }
}
