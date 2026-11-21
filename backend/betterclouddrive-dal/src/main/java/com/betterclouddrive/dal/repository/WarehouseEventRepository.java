package com.betterclouddrive.dal.repository;

import com.betterclouddrive.common.dto.WarehouseEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;
import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class WarehouseEventRepository {
    private final JdbcTemplate jdbc;

    public record Pending(long count, Instant oldest) {}

    public Pending pending() {
        return jdbc.queryForObject("SELECT count(*), min(created_at) FROM warehouse_events WHERE exported_at IS NULL",
                (rs, row) -> new Pending(rs.getLong(1), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()));
    }

    public int pruneExported(Instant before) {
        return jdbc.update("""
            DELETE FROM warehouse_events WHERE event_id IN (
                SELECT event_id FROM warehouse_events WHERE exported_at < ? ORDER BY exported_at LIMIT 1000
            )
            """, before.atOffset(ZoneOffset.UTC));
    }

    public void append(WarehouseEvent event) {
        jdbc.update("""
            INSERT INTO warehouse_events
                (event_id, deduplication_key, event_type, occurred_at, actor_user_id, team_id,
                 resource_id, attempt_id, channel, mode, outcome, byte_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (deduplication_key) DO NOTHING
            """, event.eventId(), event.deduplicationKey(), event.type().name(),
                event.occurredAt().atOffset(ZoneOffset.UTC), event.actorUserId(), event.teamId(),
                event.resourceId(), event.attemptId(), event.channel().name(), event.mode().name(),
                event.outcome().name(), event.byteCount());
    }
}
