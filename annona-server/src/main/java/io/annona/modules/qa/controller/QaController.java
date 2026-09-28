package io.annona.modules.qa.controller;

import io.annona.common.result.Result;
import io.annona.common.session.CurrentPrincipal;
import io.annona.modules.qa.dto.QaAskRequest;
import io.annona.modules.qa.dto.QaMessageResponse;
import io.annona.modules.qa.dto.QaSessionResponse;
import io.annona.modules.qa.service.QaService;
import io.annona.spi.dto.Principal;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答端点（/api/qa，/api/** 强制鉴权白名单之外）。错误码段 2500–2599；业务失败统一由
 * 全局异常处理器转 Result.error（controller 禁 try/catch）。userId/sessionId 全程 String 口径。
 *
 * <p>SSE 流（{@code POST /api/qa/messages}）不走 Result 信封：text/event-stream，
 * 事件契约 token {delta} / sources {citations[], reason} / done {messageId} / error {code,message}
 * （qa-streaming-adr §决策 3）；流建立后的业务失败（2502 等）以 error 事件下发。
 */
@RestController
@RequestMapping("/api/qa")
public class QaController {

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }

    /**
     * 发起流式提问并返回 SSE 流。body {@code {sessionId?, question}}；sessionId 缺省 =
     * 新建会话。事件顺序 sources → token* → done；检索异常 / 模型未配置等业务失败以
     * error 事件收尾（HTTP 200）。
     */
    @PostMapping(value = "/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@CurrentPrincipal Principal principal, @RequestBody QaAskRequest request) {
        return qaService.ask(principal.id(), request);
    }

    /** 当前用户会话列表（按最近活跃倒序）。 */
    @GetMapping("/sessions")
    public Result<List<QaSessionResponse>> sessions(@CurrentPrincipal Principal principal) {
        return Result.success(qaService.sessions(principal.id()));
    }

    /** 会话历史消息（含中断保留的部分内容，completed=false 供前端标注）。 */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<QaMessageResponse>> messages(@CurrentPrincipal Principal principal,
        @PathVariable String sessionId) {
        return Result.success(qaService.messages(principal.id(), sessionId));
    }
}
