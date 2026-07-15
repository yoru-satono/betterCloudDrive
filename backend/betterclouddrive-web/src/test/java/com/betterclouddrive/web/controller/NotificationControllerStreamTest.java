package com.betterclouddrive.web.controller;

import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.web.security.UserPrincipal;
import com.betterclouddrive.web.service.NotificationSseService;
import com.betterclouddrive.web.service.NotificationStreamTicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationControllerStreamTest {
    @Mock NotificationService notificationService;
    @Mock NotificationSseService notificationSseService;
    @Mock NotificationStreamTicketService ticketService;
    @InjectMocks NotificationController controller;

    @Test void streamTicketIsIssuedForAuthenticatedPrincipal() {
        var issued = new NotificationStreamTicketService.IssuedTicket("ticket", Instant.parse("2026-01-01T00:00:30Z"));
        when(ticketService.issue(42L)).thenReturn(issued);
        var response = controller.streamTicket(new UserPrincipal(42L, "alice", "jti", "USER"));
        assertThat(response.getData()).isSameAs(issued);
        verify(ticketService).issue(42L);
    }

    @Test void streamUsesTicketOwnerRatherThanCallerControlledIdentity() {
        var emitter = new SseEmitter();
        when(ticketService.consume("ticket")).thenReturn(42L);
        when(notificationService.countUnread(42L)).thenReturn(3L);
        when(notificationSseService.subscribe(42L, 3L)).thenReturn(emitter);
        assertThat(controller.stream("ticket")).isSameAs(emitter);
        verify(notificationSseService).subscribe(42L, 3L);
    }
}
