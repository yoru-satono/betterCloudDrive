package com.betterclouddrive.web.service;

import com.betterclouddrive.service.NotificationRealtimePublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Service
public class NotificationSseService implements NotificationRealtimePublisher {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId, long unreadCount) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(userId, ignored -> new CopyOnWriteArraySet<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(userId, emitter));
        emitter.onTimeout(() -> removeEmitter(userId, emitter));
        emitter.onError(error -> removeEmitter(userId, emitter));
        send(userId, emitter, "connected", unreadCount);
        return emitter;
    }

    @Override
    public void publishNotificationsChanged(Long userId, long unreadCount) {
        Set<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null || userEmitters.isEmpty()) {
            return;
        }
        userEmitters.forEach(emitter -> send(userId, emitter, "notifications-changed", unreadCount));
    }

    private void send(Long userId, SseEmitter emitter, String eventName, long unreadCount) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(Map.of("unreadCount", unreadCount)));
        } catch (IOException | IllegalStateException e) {
            log.debug("Closing notification stream for user {}", userId, e);
            removeEmitter(userId, emitter);
        }
    }

    private void removeEmitter(Long userId, SseEmitter emitter) {
        Set<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null) {
            return;
        }
        userEmitters.remove(emitter);
        if (userEmitters.isEmpty()) {
            emitters.remove(userId);
        }
    }
}
