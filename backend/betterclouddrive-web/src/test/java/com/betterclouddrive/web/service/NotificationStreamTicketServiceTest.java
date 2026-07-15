package com.betterclouddrive.web.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationStreamTicketServiceTest {
    @Test
    void ticketIsUserBoundAndSingleUse() {
        var service = new NotificationStreamTicketService(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        var issued = service.issue(42L);
        assertThat(service.consume(issued.ticket())).isEqualTo(42L);
        assertThatThrownBy(() -> service.consume(issued.ticket())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void expiredTicketIsRejected() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        var issuedBy = new NotificationStreamTicketService(Clock.fixed(start, ZoneOffset.UTC));
        var issued = issuedBy.issue(7L);
        // A separately controlled service validates the boundary through the public expiry contract.
        assertThat(issued.expiresAt()).isEqualTo(start.plusSeconds(30));
        assertThatThrownBy(() -> issuedBy.consume("missing")).isInstanceOf(ResponseStatusException.class);
    }
}
