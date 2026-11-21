package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class QuotaLegacyRepository {
    private final JdbcTemplate jdbc;
    public record Transfer(Scope scope, long owner, long delta, String status) {}

    public void exclusively(Runnable work) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT pg_try_advisory_lock(735190422)")) {
                result.next();
                if (!result.getBoolean(1)) throw new IllegalStateException("quota_cutover_already_running");
            }
            try { work.run(); }
            finally {
                try (var statement = connection.createStatement()) {
                    statement.execute("SELECT pg_advisory_unlock(735190422)");
                }
            }
            return null;
        });
    }

    public Optional<Transfer> find(Scope scope, long owner) {
        return jdbc.query("SELECT scope, owner_id, delta, status FROM quota_legacy_transfers WHERE scope=? AND owner_id=?",
                (row, i) -> new Transfer(Scope.valueOf(row.getString(1)), row.getLong(2), row.getLong(3), row.getString(4)),
                scope.name(), owner).stream().findFirst();
    }

    public List<Transfer> pending() {
        return jdbc.query("SELECT scope, owner_id, delta, status FROM quota_legacy_transfers WHERE status <> 'ACKNOWLEDGED'",
                (row, i) -> new Transfer(Scope.valueOf(row.getString(1)), row.getLong(2), row.getLong(3), row.getString(4)));
    }

    public void record(Scope scope, long owner, long delta, Long before, Long after) {
        jdbc.update("""
                INSERT INTO quota_legacy_transfers (scope, owner_id, delta, used_before, used_after, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """, scope.name(), owner, delta, before, after, before == null ? "OWNER_ABSENT" : "APPLIED");
    }

    public void acknowledge(Scope scope, long owner) {
        jdbc.update("UPDATE quota_legacy_transfers SET status='ACKNOWLEDGED', acknowledged_at=now() WHERE scope=? AND owner_id=?",
                scope.name(), owner);
    }

    public void ready(boolean value) {
        jdbc.update("UPDATE quota_accounting_state SET ready=?, updated_at=now() WHERE id=1", value);
    }
}
