package com.betterclouddrive.service.inspection;

import com.betterclouddrive.storage.StorageInventoryException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ephemeral stream-upload protection; no extra PostgreSQL connection or file contents. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActiveObjectPrefixService {
    private final StringRedisTemplate redis;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "object-prefix-heartbeat"); thread.setDaemon(true); return thread;
    });

    @PostConstruct void start() { heartbeat.scheduleWithFixedDelay(this::renew, 30, 30, TimeUnit.SECONDS); }

    public Registration register(String prefix) {
        active.add(prefix);
        return new Registration(prefix);
    }

    void renew() {
        for (String prefix : active) if (!publish(prefix)) break;
    }

    private boolean publish(String prefix) {
        if (!active.contains(prefix)) return true;
        try { redis.opsForValue().set(key(prefix), "1", Duration.ofSeconds(120)); return true; }
        catch (RuntimeException error) {
            // Inspection can fail closed on an unavailable lease store; core streaming continues.
            log.warn("Object activity lease unavailable; type={}", error.getClass().getSimpleName());
            return false;
        }
    }

    public boolean protects(String objectKey) {
        if (!objectKey.startsWith("uploads/")) return false;
        int part = objectKey.lastIndexOf(".part.");
        if (part < 0) return false;
        String prefix = objectKey.substring(0, part);
        if (active.contains(prefix)) return true;
        try { return Boolean.TRUE.equals(redis.hasKey(key(prefix))); }
        catch (RuntimeException error) { throw new StorageInventoryException("inspection_activity_unavailable"); }
    }

    private String key(String prefix) { return "storage:active-prefix:" + InspectionObservationService.fingerprint(prefix); }

    public final class Registration implements AutoCloseable {
        private final String prefix;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Registration(String prefix) { this.prefix = prefix; }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCompletion(int status) { release(); }
                        });
            } else release();
        }
        private void release() {
            active.remove(prefix);
            // Redis leases expire naturally. Request completion never waits for inspection I/O.
        }
    }

    @PreDestroy void close() { heartbeat.shutdownNow(); }
}
