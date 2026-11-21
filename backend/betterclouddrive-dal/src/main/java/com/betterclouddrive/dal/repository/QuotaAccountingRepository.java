package com.betterclouddrive.dal.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** SQL and row locking only; accounting policy belongs to the service. */
@Repository
@RequiredArgsConstructor
public class QuotaAccountingRepository {
    private final JdbcTemplate jdbc;

    public enum Scope { PERSONAL, TEAM }
    public record Balance(long used, long quota, long revision) {}
    public record Owner(Scope scope, long id) {}

    private String table(Scope scope) { return scope == Scope.PERSONAL ? "users" : "teams"; }

    public boolean ready() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT ready FROM quota_accounting_state WHERE id = 1", Boolean.class));
    }

    public void limitLockWait() { jdbc.execute("SET LOCAL lock_timeout = '5s'"); }

    public boolean active(Scope scope, long owner) {
        return jdbc.query("SELECT deleted_at IS NULL FROM " + table(scope) + " WHERE id=?",
                (row, index) -> row.getBoolean(1), owner).stream().findFirst().orElse(false);
    }

    public Optional<Balance> find(Scope scope, long owner, boolean lock) {
        return jdbc.query("SELECT storage_used, storage_quota, quota_revision FROM " + table(scope)
                        + " WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                (row, index) -> new Balance(row.getLong(1), row.getLong(2), row.getLong(3)), owner).stream().findFirst();
    }

    public void setUsed(Scope scope, long owner, long used) {
        if (jdbc.update("UPDATE " + table(scope)
                + " SET storage_used = ?, quota_revision = quota_revision + 1 WHERE id = ?", used, owner) != 1) {
            throw new IllegalStateException("quota_owner_disappeared");
        }
    }

    public Optional<Owner> fileOwner(long fileId) {
        return jdbc.query("SELECT user_id, team_id FROM files WHERE id = ?", (row, index) ->
                row.getObject("team_id") == null ? new Owner(Scope.PERSONAL, row.getLong("user_id"))
                        : new Owner(Scope.TEAM, row.getLong("team_id")), fileId).stream().findFirst();
    }
}
