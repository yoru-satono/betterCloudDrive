package com.betterclouddrive.service.quota;

import com.betterclouddrive.dal.repository.QuotaAccountingRepository;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Scope;
import com.betterclouddrive.dal.repository.QuotaLegacyRepository;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Explicit offline operation, never scheduled or exposed over HTTP. */
public class QuotaCutoverService {
    private final QuotaAccountingRepository accounting;
    private final QuotaLegacyRepository legacy;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transactions;
    private static final DefaultRedisScript<Long> ACK = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then return 1 end
            if value == ARGV[1] then redis.call('DEL', KEYS[1]); return 1 end
            return 0
            """, Long.class);

    public QuotaCutoverService(QuotaAccountingRepository accounting, QuotaLegacyRepository legacy,
                              StringRedisTemplate redis, TransactionTemplate transactions) {
        this.accounting = accounting;
        this.legacy = legacy;
        this.redis = redis;
        this.transactions = transactions;
    }

    public void run(boolean writersStopped) {
        if (!writersStopped) throw new IllegalArgumentException("writers_stopped_confirmation_required");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("quota_cutover_requires_no_business_transaction");
        }
        legacy.exclusively(() -> {
            transactions.executeWithoutResult(status -> legacy.ready(false));
            for (Scope scope : Scope.values()) {
                String prefix = prefix(scope);
                try (var cursor = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(500).build())) {
                    while (cursor.hasNext()) transfer(scope, prefix, cursor.next());
                }
            }
            // Recover a crash after applying PostgreSQL but before acknowledging Redis.
            for (var transfer : legacy.pending()) acknowledge(transfer.scope(), transfer.owner(), Long.toString(transfer.delta()));
            for (Scope scope : Scope.values()) {
                try (var cursor = redis.scan(ScanOptions.scanOptions().match(prefix(scope) + "*").count(500).build())) {
                    if (cursor.hasNext()) throw new IllegalStateException("legacy_writes_still_present");
                }
            }
            transactions.executeWithoutResult(status -> legacy.ready(true));
        });
    }

    private void transfer(Scope scope, String prefix, String key) {
        long owner = Long.parseLong(key.substring(prefix.length()));
        String value = redis.opsForValue().get(key);
        if (value == null) return;
        long delta = Long.parseLong(value);
        transactions.executeWithoutResult(status -> {
            var recorded = legacy.find(scope, owner);
            if (recorded.isPresent()) {
                if (recorded.get().delta() != delta || "ACKNOWLEDGED".equals(recorded.get().status())) {
                    throw new IllegalStateException("legacy_value_changed_after_cutover");
                }
                return;
            }
            var balance = accounting.find(scope, owner, true);
            if (balance.isEmpty()) {
                legacy.record(scope, owner, delta, null, null);
            } else {
                long updated = Math.addExact(balance.get().used(), delta);
                accounting.setUsed(scope, owner, updated);
                legacy.record(scope, owner, delta, balance.get().used(), updated);
            }
        });
        acknowledge(scope, owner, value);
    }

    private void acknowledge(Scope scope, long owner, String value) {
        Long acknowledged = redis.execute(ACK, List.of(prefix(scope) + owner), value);
        if (!Long.valueOf(1).equals(acknowledged)) throw new IllegalStateException("legacy_value_changed_after_cutover");
        transactions.executeWithoutResult(status -> legacy.acknowledge(scope, owner));
    }

    private String prefix(Scope scope) {
        return scope == Scope.PERSONAL ? "storage:incr:" : "team:storage:incr:";
    }
}
