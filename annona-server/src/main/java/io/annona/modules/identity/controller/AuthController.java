package io.annona.modules.identity.controller;

import io.annona.common.result.Result;
import io.annona.config.web.CurrentPrincipal;
import io.annona.modules.identity.dto.AuthUserResponse;
import io.annona.modules.identity.dto.ChangePasswordRequest;
import io.annona.modules.identity.dto.LoginRequest;
import io.annona.modules.identity.dto.RegisterRequest;
import io.annona.modules.identity.service.AuthUserRegistrar;
import io.annona.modules.identity.service.ChangePasswordService;
import io.annona.modules.identity.service.LoginOutcome;
import io.annona.modules.identity.service.LoginService;
import io.annona.modules.identity.service.SessionProperties;
import io.annona.modules.identity.service.SessionService;
import io.annona.spi.dto.Principal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证入口：注册 / 登录 / 登出。会话经 HttpOnly Cookie 承载。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthUserRegistrar registrar;
    private final LoginService loginService;
    private final SessionService sessionService;
    private final ChangePasswordService changePasswordService;
    private final SessionProperties sessionProperties;

    public AuthController(AuthUserRegistrar registrar,
                          LoginService loginService,
                          SessionService sessionService,
                          ChangePasswordService changePasswordService,
                          SessionProperties sessionProperties) {
        this.registrar = registrar;
        this.loginService = loginService;
        this.sessionService = sessionService;
        this.changePasswordService = changePasswordService;
        this.sessionProperties = sessionProperties;
    }

    @PostMapping("/register")
    public Result<AuthUserResponse> register(@RequestBody RegisterRequest request) {
        return Result.success(registrar.register(request.email(), request.password()));
    }

    @PostMapping("/login")
    public Result<Void> login(@RequestBody LoginRequest request,
                              HttpServletRequest servletRequest,
                              HttpServletResponse servletResponse) {
        String ip = servletRequest.getRemoteAddr();
        String userAgent = servletRequest.getHeader("User-Agent");
        LoginOutcome outcome = loginService.login(request.email(), request.password(), ip, null, userAgent);
        ResponseCookie cookie = ResponseCookie.from(sessionProperties.getCookie(), outcome.token())
            .httpOnly(true)
            .path("/")
            .sameSite("Lax")
            .maxAge(sessionProperties.getTtl())
            .build();
        servletResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return Result.success();
    }

    @PostMapping("/password")
    public Result<Void> changePassword(@CurrentPrincipal Principal principal,
                                       @RequestBody ChangePasswordRequest request) {
        changePasswordService.change(principal.id(), request.oldPassword(), request.newPassword());
        return Result.success();
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        String token = readSessionCookie(servletRequest);
        if (token != null) {
            sessionService.logout(token);
        }
        ResponseCookie cleared = ResponseCookie.from(sessionProperties.getCookie(), "")
            .httpOnly(true).path("/").sameSite("Lax").maxAge(0).build();
        servletResponse.addHeader(HttpHeaders.SET_COOKIE, cleared.toString());
        return Result.success();
    }

    private String readSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (sessionProperties.getCookie().equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
