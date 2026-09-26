package io.annona.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 纯逻辑 + servlet mock 测试：{@link TraceIdFilter} 的三条契约。
 * 不需要 Web 服务器，因此本机可跑（无 tag）。
 */
@DisplayName("TraceIdFilter 链路标识（P0 可观测基线）")
class TraceIdFilterTest {

    private static final Pattern HEX_16 = Pattern.compile("[0-9a-f]{16}");

    private final TraceIdFilter filter = new TraceIdFilter();

    @Nested
    @DisplayName("入站头清洗")
    class InboundHeader {

        @Test
        @DisplayName("合规的 X-Trace-Id 被沿用（跨服务串联需要）")
        void keepsWellFormedInboundId() {
            assertThat(TraceIdFilter.resolveTraceId("abcDEF-1.2_3"))
                .isEqualTo("abcDEF-1.2_3");
        }

        @Test
        @DisplayName("含换行/空格/超长/空白的入站值一律重新生成")
        void rejectsUnsafeInboundId() {
            for (String dirty : new String[] {
                "ok\nfake-log-line", "has space", "", "  ", "x".repeat(65), "emoji😀"
            }) {
                assertThat(TraceIdFilter.resolveTraceId(dirty))
                    .as("脏输入 %s 不应被沿用", dirty)
                    .hasSize(16)
                    .matches(HEX_16.pattern());
            }
        }

        @Test
        @DisplayName("无入站头时生成 16 位 hex，且两次调用不同")
        void generatesWhenAbsent() {
            String first = TraceIdFilter.resolveTraceId(null);
            String second = TraceIdFilter.resolveTraceId(null);

            assertThat(first).hasSize(16).matches(HEX_16.pattern());
            assertThat(second).isNotEqualTo(first);
        }
    }

    @Nested
    @DisplayName("过滤链行为")
    class FilterBehavior {

        @Test
        @DisplayName("请求结束后 MDC 被清理，线程复用不会串号")
        void clearsMdcAfterChain() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/meta/ping");
            MockHttpServletResponse response = new MockHttpServletResponse();
            StringBuilder inChain = new StringBuilder();

            filter.doFilterInternal(request, response, (req, res) ->
                inChain.append(MDC.get(TraceIdFilter.TRACE_ID)));

            assertThat(inChain.toString())
                .as("下游必须能看到 traceId")
                .matches("[0-9a-f]{16}");
            assertThat(MDC.get(TraceIdFilter.TRACE_ID))
                .as("finally 必须 remove，否则线程复用会串号")
                .isNull();
            assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo(inChain.toString());
        }
    }
}
