package io.annona.modules.identity.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.spi.dto.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前登录用户。无有效会话时参数解析器抛 UNAUTHORIZED（HTTP 200 + Result.error）。 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    @GetMapping
    public Result<Principal> me(@CurrentPrincipal Principal principal) {
        return Result.success(principal);
    }
}
