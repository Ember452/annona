package io.annona.config.web;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.identity.dto.AuthUserResponse;
import io.annona.modules.identity.service.UserQueryService;
import io.annona.spi.dto.Principal;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 解析 {@link CurrentPrincipal} 参数：从请求属性取 userId（{@link SessionAuthFilter} 注入），
 * 加载主体视图并组装 {@link Principal}。无有效会话 → {@link ErrorCode#UNAUTHORIZED}。
 */
@Component
public class CurrentPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    private final ObjectProvider<UserQueryService> userQueryService;

    public CurrentPrincipalArgumentResolver(ObjectProvider<UserQueryService> userQueryService) {
        this.userQueryService = userQueryService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentPrincipal.class)
            && Principal.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object userId = webRequest.getAttribute(SessionAuthFilter.ATTR_USER_ID, RequestAttributes.SCOPE_REQUEST);
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        AuthUserResponse user = userQueryService.getObject().loadByUserId(userId.toString());
        return new Principal(user.id(), user.email(), Set.of(user.role()));
    }
}
