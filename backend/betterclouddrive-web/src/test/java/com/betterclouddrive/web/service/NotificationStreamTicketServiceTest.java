package com.betterclouddrive.web.service;

import org.junit.jupiter.api.Test;
import com.betterclouddrive.common.exception.BusinessException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationStreamTicketServiceTest {
    @Test
    void ticketIsUserBoundAndSingleUse() {
        var service = new NotificationStreamTicketService(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        var issued = service.issue(42L);
        assertThat(service.consume(issued.ticket())).isEqualTo(42L);
        assertThatThrownBy(() -> service.consume(issued.ticket())).isInstanceOf(BusinessException.class);
    }

    @Test
    void expiredTicketIsRejected() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        var clock = new MutableClock(start);
        var service = new NotificationStreamTicketService(clock);
        var issued = service.issue(7L);
        assertThat(issued.expiresAt()).isEqualTo(start.plusSeconds(30));
        clock.advanceSeconds(30);
        assertThatThrownBy(() -> service.consume(issued.ticket())).isInstanceOf(BusinessException.class);
    }

    @Test
    void blankAndForgedTicketsAreRejected() {
        var service = new NotificationStreamTicketService(Clock.systemUTC());
        assertThatThrownBy(() -> service.consume(" ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.consume("forged")).isInstanceOf(BusinessException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
