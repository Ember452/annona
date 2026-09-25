package io.annona.spi.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.annona.spi.dto.DecisionContext;
import io.annona.spi.dto.InterviewPlan;
import io.annona.spi.dto.ModelChatMessage;
import io.annona.spi.dto.ModelOptions;
import io.annona.spi.dto.ModelResponse;
import io.annona.spi.dto.Principal;
import io.annona.spi.dto.RetrievalQuery;
import io.annona.spi.dto.SignalSnapshot;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 五个 Fake 的 smoke test（P0 出口条件 ③ 的直接证据）。
 *
 * <p>断言两件事：
 * <ul>
 *   <li>每个 Fake 都能 {@code new} 出来且关键常量方法（{@code mode/name/backend/key}）
 *       返回约定的字符串；</li>
 *   <li>每个 Fake 的核心方法不抛异常、返回非 null 的形状（{@code Optional} / 集合 /
 *       record），语义正确（identity 返本地用户、model 返固定文本、retriever 返空集、
 *       signalReader 返零值快照、decisionRule 返 empty）。</li>
 * </ul>
 *
 * <p>P0 阶段 fake 的下游消费者还没写，本测试是"五个骨架都能被外部按接口契约使用"
 * 的最小验证。P1a/P1b 起 fake 会出现在业务模块的 {@code @MockBean} 里，那时才有
 * 端到端覆盖，本类不做重复。
 */
@DisplayName("SPI Fake 五件套 smoke test（P0 出口 ③）")
class SpiFakesSmokeTest {

    @Test
    @DisplayName("FakeIdentityProvider：mode=none；任何 token 都返回 local principal")
    void fakeIdentityProviderReturnsLocalPrincipal() {
        FakeIdentityProvider sut = new FakeIdentityProvider();
        assertEquals("none", sut.mode());
        Optional<Principal> principal = sut.authenticate("any-token-at-all");
        assertTrue(principal.isPresent());
        assertEquals("local", principal.get().id());
        assertTrue(principal.get().roles().contains("USER"));
    }

    @Test
    @DisplayName("FakeModelProvider：name=fake；chat 返回固定文本 + 非零 usage")
    void fakeModelProviderReturnsFixedReply() {
        FakeModelProvider sut = new FakeModelProvider();
        assertEquals("fake", sut.name());
        List<ModelChatMessage> msgs = List.of(new ModelChatMessage("user", "hello world this is prompt"));
        ModelResponse resp = sut.chat(msgs, ModelOptions.defaults());
        assertEquals("fake-model-response", resp.content());
        assertNotNull(resp.usage());
        assertTrue(resp.usage().promptTokens() > 0, "prompt tokens estimated from messages");
        assertTrue(resp.usage().completionTokens() >= 0);
        assertEquals("fake-chat-v0", resp.model());
    }

    @Test
    @DisplayName("FakeRetriever：backend=fake；默认 retrieve 返回空集，可注入 preset")
    void fakeRetrieverSupportsPreset() {
        FakeRetriever empty = new FakeRetriever();
        assertEquals("fake", empty.backend());
        RetrievalQuery q = new RetrievalQuery("anything", 5, "u-1", List.of());
        assertTrue(empty.retrieve(q).isEmpty());

        FakeRetriever withHits = new FakeRetriever(
            List.of(new io.annona.spi.dto.RetrievalHit("doc-1", "chunk-1", "snippet", 0.9)));
        assertEquals(1, withHits.retrieve(q).size());
    }

    @Test
    @DisplayName("FakeLearningSignalReader：返回零值快照（totalStudy=0 sampleSize=0 completionPercent=0）")
    void fakeSignalReaderReturnsZeroSnapshot() {
        FakeLearningSignalReader sut = new FakeLearningSignalReader();
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        SignalSnapshot snap = sut.read("u-1", from, to);
        assertEquals("u-1", snap.userId());
        assertEquals(from, snap.from());
        assertEquals(to, snap.to());
        assertEquals(Duration.ZERO, snap.totalStudy());
        assertEquals(0, snap.sampleSize());
        assertEquals(Integer.valueOf(0), snap.completionPercent());
    }

    @Test
    @DisplayName("FakeDecisionRule：key=FAKE；apply 永远返回 empty（等价于关掉这条规则）")
    void fakeDecisionRuleNeverMatches() {
        FakeDecisionRule sut = new FakeDecisionRule();
        assertEquals("FAKE", sut.key());
        DecisionContext ctx = new DecisionContext("u-1", LocalDate.now(),
            new FakeLearningSignalReader().read("u-1", LocalDate.now(), LocalDate.now()));
        Optional<io.annona.spi.planner.DecisionRule.MutableOutcome> outcome =
            sut.apply(ctx, InterviewPlan.empty());
        assertFalse(outcome.isPresent());
    }
}
