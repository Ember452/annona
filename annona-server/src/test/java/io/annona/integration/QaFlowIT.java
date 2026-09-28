package io.annona.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.annona.common.exception.BusinessException;
import io.annona.common.exception.ErrorCode;
import io.annona.infrastructure.llm.FakeStreamingChatProvider;
import io.annona.modules.identity.dto.AuthUserResponse;
import io.annona.modules.identity.service.AuthUserRegistrar;
import io.annona.modules.qa.dto.QaAskRequest;
import io.annona.modules.qa.dto.QaCitation;
import io.annona.modules.qa.dto.QaMessageResponse;
import io.annona.modules.qa.dto.QaSessionResponse;
import io.annona.modules.qa.entity.QaMessageEntity;
import io.annona.modules.qa.entity.QaSessionEntity;
import io.annona.modules.qa.repository.QaMessageRepository;
import io.annona.modules.qa.repository.QaSessionRepository;
import io.annona.modules.qa.service.QaService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * qa 闭环（真实 PG，qa-streaming-adr §决策 4 的证伪点）：V6 建表由上下文启动
 * （Flyway + ddl-auto=validate）背书；fake chat 走完整占位→回填流；citations JSONB
 * 往返、会话级联删除、跨用户不可见。本机不跑；CI 的 docker-it job 执行（dockerless ADR）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "annona.model.chat.provider=fake")
@ActiveProfiles("docker")
@Tag("docker")
@DisplayName("qa：流式问答闭环（真实 PG）")
class QaFlowIT {

    @Autowired
    private AuthUserRegistrar registrar;
    @Autowired
    private QaService qaService;
    @Autowired
    private QaSessionRepository sessionRepository;
    @Autowired
    private QaMessageRepository messageRepository;

    private static String uniqueEmail() {
        return "p1a08-" + UUID.randomUUID() + "@example.test";
    }

    @Test
    @DisplayName("fake chat 全流程：占位落库→流式回填 completed=true，会话历史完整，刷新可读")
    void fakeChatFullFlow() throws InterruptedException {
        AuthUserResponse user = registrar.register(uniqueEmail(), "GoodPass123");

        qaService.ask(user.id(), new QaAskRequest(null, "知识库里有什么？"));

        // 回填在 AI-IO 线程异步完成，轮询到终态（fake 无延迟，秒级内必达）
        String sessionId = qaService.sessions(user.id()).get(0).id().toString();
        List<QaMessageResponse> messages = qaService.messages(user.id(), sessionId);
        Instant deadline = Instant.now().plusSeconds(10);
        while (messages.stream().noneMatch(m -> QaMessageEntity.TYPE_ASSISTANT.equals(m.type()) && m.completed())
            && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
            messages = qaService.messages(user.id(), sessionId);
        }

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).type()).isEqualTo(QaMessageEntity.TYPE_USER);
        assertThat(messages.get(0).content()).isEqualTo("知识库里有什么？");
        assertThat(messages.get(1).type()).isEqualTo(QaMessageEntity.TYPE_ASSISTANT);
        assertThat(messages.get(1).completed()).isTrue();
        // 无文档 → 空命中 → 空引用列表；诊断 reason 随消息持久化（V7），历史视图同样可解释
        assertThat(messages.get(1).citations()).isEmpty();
        assertThat(messages.get(1).missReason()).isEqualTo("NO_READY_DOC");
        assertThat(messages.get(1).content()).isEqualTo(FakeStreamingChatProvider.RESPONSE);
    }

    @Test
    @DisplayName("citations JSONB 往返：写入后读回字段一致（Hibernate @JdbcTypeCode 证伪点）")
    void citationsJsonbRoundTrip() {
        AuthUserResponse user = registrar.register(uniqueEmail(), "GoodPass123");
        UUID userId = UUID.fromString(user.id());

        QaSessionEntity session = new QaSessionEntity();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.setTitle("引用往返");
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);

        QaCitation citation = new QaCitation("doc-1", "chunk-1", 3, "Java > 基础", "引用片段。", 0.87);
        QaMessageEntity assistant = new QaMessageEntity();
        assistant.setId(UUID.randomUUID());
        assistant.setSessionId(session.getId());
        assistant.setMessageOrder(2);
        assistant.setType(QaMessageEntity.TYPE_ASSISTANT);
        assistant.setContent("回答正文");
        assistant.setCompleted(true);
        assistant.setCitations(List.of(citation));
        messageRepository.save(assistant);

        QaMessageEntity reloaded = messageRepository.findById(assistant.getId()).orElseThrow();
        assertThat(reloaded.getCitations()).hasSize(1);
        assertThat(reloaded.getCitations().get(0).chunkId()).isEqualTo("chunk-1");
        assertThat(reloaded.getCitations().get(0).chunkIndex()).isEqualTo(3);
        assertThat(reloaded.getCitations().get(0).headingPath()).isEqualTo("Java > 基础");
        assertThat(reloaded.getCitations().get(0).score()).isEqualTo(0.87);
    }

    @Test
    @DisplayName("跨用户不可见：B 读 A 的会话报 2500，不泄露存在性")
    void foreignSessionHidden() {
        AuthUserResponse owner = registrar.register(uniqueEmail(), "GoodPass123");
        AuthUserResponse stranger = registrar.register(uniqueEmail(), "GoodPass123");

        qaService.ask(owner.id(), new QaAskRequest(null, "占位问题"));
        String sessionId = qaService.sessions(owner.id()).get(0).id().toString();

        assertThatThrownBy(() -> qaService.messages(stranger.id(), sessionId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCode.QA_SESSION_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("删除会话级联清理消息（FK on delete cascade）")
    void deleteSessionCascadesMessages() {
        AuthUserResponse user = registrar.register(uniqueEmail(), "GoodPass123");
        qaService.ask(user.id(), new QaAskRequest(null, "将被删除的问题"));
        QaSessionResponse session = qaService.sessions(user.id()).get(0);
        UUID sessionId = session.id();

        assertThat(messageRepository.findBySessionIdOrderByMessageOrderAsc(sessionId)).isNotEmpty();
        sessionRepository.deleteById(sessionId);
        assertThat(messageRepository.findBySessionIdOrderByMessageOrderAsc(sessionId)).isEmpty();
    }
}
