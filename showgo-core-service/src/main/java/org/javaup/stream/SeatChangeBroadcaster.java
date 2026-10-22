package org.javaup.stream;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.javaup.kafka.message.SeatChangeMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 座位变更实时推送：以 SSE 把座位图变化推给正在选座的用户。
 *
 * <p>多实例部署时，本实例只持有连接到自己的一部分用户，
 * 变更通过 Kafka 广播到所有实例，再由各实例推给自己持有的连接。</p>
 */
@Slf4j
@Component
public class SeatChangeBroadcaster {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long sessionId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> {
            List<SseEmitter> list = emitters.get(sessionId);
            if (list != null) {
                list.remove(emitter);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            cleanup.run();
        }
        return emitter;
    }

    public void broadcast(Long sessionId, SeatChangeMessage message) {
        List<SseEmitter> list = emitters.get(sessionId);
        if (CollectionUtil.isEmpty(list)) {
            return;
        }
        String payload = JSON.toJSONString(message);
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("seat-change").data(payload));
            } catch (Exception e) {
                list.remove(emitter);
                emitter.complete();
            }
        }
    }
}
