package io.annona.modules.study.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.annona.common.presence.PresencePort;
import io.annona.common.result.Result;
import io.annona.modules.study.dto.PresenceResponse;
import io.annona.spi.dto.Principal;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PresenceController} 切片：轮询即心跳（touch 自己）+ 窗口计数透传。
 * Redis ZSET 的滑窗剪枝与 TTL 兜底由 {@code PresenceFlowIT} 在真 Redis 上钉。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("匿名共学端点切片")
class PresenceControllerTest {

    private static final String USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Mock
    private PresencePort presence;

    @Test
    @DisplayName("读取同时刷新自己在场，返回窗口内在线数")
    void presenceTouchesSelfAndReturnsCount() {
        when(presence.activeCount(any(), eq(Duration.ofSeconds(45)))).thenReturn(7L);

        Result<PresenceResponse> result = new PresenceController(presence)
            .presence(new Principal(USER_ID, "mock", Set.of("USER")));

        verify(presence).touch(eq(UUID.fromString(USER_ID)), any());
        assertThat(result.getData().online()).isEqualTo(7L);
    }
}
