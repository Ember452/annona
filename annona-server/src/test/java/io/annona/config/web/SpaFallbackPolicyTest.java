package io.annona.config.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SpaFallbackPolicy：SPA 回退真值表（守住 D19 的 404 边界）")
class SpaFallbackPolicyTest {

    @Nested
    @DisplayName("前端路由 → 回退 index.html")
    class ClientRoutes {

        @Test
        @DisplayName("单层与多层 history 路由都回退")
        void routesFallBack() {
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("study")).isTrue();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("interview")).isTrue();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("qa")).isTrue();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("plan")).isTrue();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("interview/123")).isTrue();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("study/2026/09/26")).isTrue();
        }

        @Test
        @DisplayName("前导斜杠与大小写不影响判定")
        void normalizesInput() {
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("/Study")).isTrue();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("/INTERVIEW/1")).isTrue();
        }
    }

    @Nested
    @DisplayName("API / 运维端点 → 绝不回退（保持真实 404，不破坏 D19）")
    class ReservedNeverFallBack {

        @Test
        @DisplayName("api/actuator/v3/swagger/ws 前缀一律 false")
        void reservedPrefixes() {
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("api/nonexistent")).isFalse();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("api/auth/login")).isFalse();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("actuator/health")).isFalse();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("v3/api-docs")).isFalse();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("swagger-ui/index")).isFalse();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("ws/voice")).isFalse();
        }
    }

    @Nested
    @DisplayName("带扩展名的资源请求 → 不回退（缺失的 JS/CSS/图片必须真 404）")
    class AssetMissesNeverFallBack {

        @Test
        @DisplayName("末段含点视为资源文件")
        void extensionMeansAsset() {
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("assets/missing-BgN5.js")).isFalse();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("favicon.ico")).isFalse();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("index.html")).isFalse();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("some/route.js")).isFalse();
        }
    }

    @Nested
    @DisplayName("边界输入")
    class EdgeCases {

        @Test
        @DisplayName("null / 空串 / 根路径 → false")
        void nullEmptyRoot() {
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex(null)).isFalse();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("")).isFalse();
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("/")).isFalse();
        }

        @Test
        @DisplayName("路径中间有点但末段无点 → 仍是路由（回退）")
        void dotInMiddleNotLastSegment() {
            // 例如目录名带点、最后一段是路由名 → 判定看末段
            assertThat(SpaFallbackPolicy.shouldFallbackToIndex("v1.2/study")).isTrue();
        }
    }
}
