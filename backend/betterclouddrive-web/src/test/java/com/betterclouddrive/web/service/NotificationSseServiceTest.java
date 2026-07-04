package com.betterclouddrive.web.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationSseServiceTest {

    @Test
    void subscribe_shouldReturnLongLivedEmitterAndAcceptPublish() {
        NotificationSseService service = new NotificationSseService();

        SseEmitter emitter = service.subscribe(2L, 3L);
        service.publishNotificationsChanged(2L, 4L);
        service.publishNotificationsChanged(3L, 1L);

        assertThat(emitter).isNotNull();
    }
}
