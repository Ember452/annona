package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.annona.common.presence.PresencePort;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 匿名共学在<b>真 Redis</b> 上的集测（CI docker-it 专属，本机无 Redis 不跑）。
 *
 * <p>本机测不到而必须真库钉的：① 滑窗语义——窗口内 touch 计入、窗口陈旧成员被读时
 * 剪枝清掉（removeRangeByScore 是实现承诺，mock 探不到）；② member 覆盖语义——同一
 * 用户重复 touch 不产生重复计数；③ StringCodec 下 userId 字符串往返无损。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("匿名共学真库集测（P2-05）")
class PresenceFlowIT {

    @Autowired
    private PresencePort presence;

    @Test
    @DisplayName("窗口内多人计数正确、重复 touch 去重、窗口外剪枝归零")
    void windowCountAndPruning() {
        Instant now = Instant.now();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        presence.touch(alice, now);
        presence.touch(bob, now.minusSeconds(5));
        presence.touch(alice, now.minusSeconds(1)); // 重复刷新，不得重复计数

        assertThat(presence.activeCount(now, Duration.ofSeconds(45))).isEqualTo(2);

        // 时间前进 60s：两人的最后在场都落在 45s 窗口外 → 读时剪枝清光
        Instant later = now.plusSeconds(60);
        assertThat(presence.activeCount(later, Duration.ofSeconds(45))).isZero();
    }
}
