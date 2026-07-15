package com.betterclouddrive.web.service;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NotificationStreamTicketService {
    private static final long TTL_SECONDS = 30;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Clock clock;

    public NotificationStreamTicketService() { this(Clock.systemUTC()); }
    NotificationStreamTicketService(Clock clock) { this.clock = clock; }

    public IssuedTicket issue(Long userId) {
        Instant expiresAt = clock.instant().plus(TTL_SECONDS, ChronoUnit.SECONDS);
        String value = UUID.randomUUID().toString();
        tickets.put(value, new Ticket(userId, expiresAt));
        return new IssuedTicket(value, expiresAt);
    }

    public Long consume(String value) {
        if (value == null || value.isBlank()) throw unauthorized();
        Ticket ticket = tickets.remove(value);
        if (ticket == null || !ticket.expiresAt().isAfter(clock.instant())) throw unauthorized();
        return ticket.userId();
    }

    private BusinessException unauthorized() {
        return new BusinessException(ApiCode.UNAUTHORIZED, "Invalid or expired stream ticket");
    }
    private record Ticket(Long userId, Instant expiresAt) {}
    public record IssuedTicket(String ticket, Instant expiresAt) {}
}
