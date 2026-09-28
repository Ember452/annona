package io.annona.modules.qa.dto;

/**
 * 一次流式提问的请求体（{@code POST /api/qa/messages}，响应为 SSE 流）。
 *
 * @param sessionId 会话 id；{@code null}/空白 = 新建会话（首问标题取问题前 20 字）
 * @param question  用户问题；空白报 1001
 */
public record QaAskRequest(String sessionId, String question) {
}
