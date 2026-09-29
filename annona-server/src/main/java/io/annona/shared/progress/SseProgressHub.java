package io.annona.shared.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.annona.shared.progress.ProgressEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 进度 SSE 枢纽（knowledge 入库先用，P1b-02 出题复用；仓库首个 SSE 先例）：消费者线程 publish，controller 订阅。
 * SseEmitter 在 controller 请求内创建（无 web 专属 bean，NONE 上下文连坐风险为零，
 * SessionAuthFilter 先例口径）；发送失败（客户端断开）即移除，不重连不缓存——
 * 断线降级由前端轮询 status 接口承担（决策 10）。
 */
@Service
public class SseProgressHub {

    private static final Logger log = LoggerFactory.getLogger(SseProgressHub.class);

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 订阅某文档的进度流；timeout=0 表示不过期，由客户端断开或完成事件结束。 */
    public SseEmitter subscribe(UUID docId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> list = emitters.computeIfAbsent(docId, key -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(error -> list.remove(emitter));
        return emitter;
    }

    /** 向该文档的全部订阅者广播进度；发送失败静默摘除（连接已断）。 */
    public void publish(UUID docId, ProgressEvent event) {
        List<SseEmitter> list = emitters.get(docId);
        if (list == null || list.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("进度事件序列化失败 docId={}", docId, e);
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(payload));
            } catch (Exception e) {
                list.remove(emitter);
            }
        }
        if (list.isEmpty()) {
            emitters.remove(docId); // 流结束后清掉空列表，map 不残留死 key
        }
    }
}
