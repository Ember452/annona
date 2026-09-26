package io.annona.config.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.common.result.Result;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * {@link GlobalExceptionHandler} 的两条契约（2026-09-26 评审整改时补，阶段总结 D19）：
 * <ol>
 *   <li><b>响应体形状</b>：任何异常都转成 {@code Result{code,message,data:null}}，
 *       未知异常<b>不得</b>把内部 message/栈透出到对外响应；</li>
 *   <li><b>状态码分层</b>：业务失败 200，路由与传输层错误必须是真实状态码。
 *       这条用反射读 {@code @ResponseStatus} 来锁死——它是本次整改的核心，
 *       一旦有人把兜底改回 200，本测试立刻红。</li>
 * </ol>
 *
 * <p>纯对象测试，不起 Web 容器（本机无 Docker 也能跑）。
 */
@DisplayName("全局异常处理器的响应体与状态码契约（D19）")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Nested
    @DisplayName("响应体形状")
    class BodyShape {

        @Test
        @DisplayName("BusinessException 透传 code 与面向用户的 message")
        void businessPassesThroughCodeAndMessage() {
            Result<Void> result = handler.handleBusiness(
                new BusinessException(ErrorCode.NOT_FOUND, "方向不存在"));

            assertThat(result.getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
            assertThat(result.getMessage()).isEqualTo("方向不存在");
            assertThat(result.getData()).isNull();
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("未知异常只给固定文案，不泄露原始 message")
        void unknownNeverLeaksInternalMessage() {
            Result<Void> result = handler.handleUnknown(
                new IllegalStateException("SQL 语句泄露：select secret from user_credential"));

            assertThat(result.getMessage()).isEqualTo("系统繁忙，请稍后重试");
            assertThat(result.getMessage()).doesNotContain("SQL", "secret", "user_credential");
            assertThat(result.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
        }
    }

    @Nested
    @DisplayName("状态码分层（反射锁死）")
    class StatusContract {

        @Test
        @DisplayName("业务失败保持 HTTP 200（前端靠 code 分流）")
        void businessStaysHttp200() {
            assertThat(statusOf("handleBusiness", BusinessException.class)).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("404 / 405 / 400 / 500 必须是真实状态码，不得回落到 200")
        void transportErrorsUseRealStatus() {
            assertThat(statusOf("handleNotFound", NoResourceFoundException.class))
                .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(statusOf("handleMethodNotSupported", HttpRequestMethodNotSupportedException.class))
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(statusOf("handleUnreadableBody", HttpMessageNotReadableException.class))
                .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(statusOf("handleUnknown", Throwable.class))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        private HttpStatus statusOf(String methodName, Class<?> paramType) {
            try {
                Method method = GlobalExceptionHandler.class.getMethod(methodName, paramType);
                ResponseStatus annotation = method.getAnnotation(ResponseStatus.class);
                assertThat(annotation)
                    .as("%s(%s) 必须显式标 @ResponseStatus，否则 Spring 会按 200 处理",
                        methodName, paramType.getSimpleName())
                    .isNotNull();
                return annotation.value();
            } catch (NoSuchMethodException e) {
                throw new AssertionError(
                    "GlobalExceptionHandler 缺少 " + methodName + "(" + paramType.getSimpleName() + ")；"
                        + "若有意合并或改名，请同步更新本测试与 docs/annona-项目设计文档.md §11", e);
            }
        }
    }
}
