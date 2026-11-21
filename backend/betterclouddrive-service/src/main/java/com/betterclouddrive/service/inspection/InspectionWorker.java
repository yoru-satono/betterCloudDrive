package com.betterclouddrive.service.inspection;

import com.betterclouddrive.dal.repository.InspectionFindingRepository;
import com.betterclouddrive.dal.repository.InspectionRecords.*;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository;
import com.betterclouddrive.dal.repository.StorageInspectionRepository;
import com.betterclouddrive.storage.StorageInventoryException;
import com.betterclouddrive.storage.StorageInventoryService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionWorker {
    private final StorageInspectionRepository repository;
    private final InspectionFindingRepository findings;
    private final QuotaAccountingRepository accounting;
    private final StorageInventoryService storage;
    private final InspectionRules rules;
    private final InspectionObservationService observations;
    private final InspectionProperties properties;
    private final InspectionMetrics metrics;
    private final PlatformTransactionManager transactionManager;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "storage-inspection"); thread.setDaemon(true); return thread;
    });
    private volatile boolean stopping;
    private long nextList;
    private long nextHead;

    /** Called by a lightweight scheduler; all scanning happens on the dedicated executor. */
    public void dispatch() {
        if (stopping || !properties.isEnabled() || !accounting.ready() || !busy.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try { work(); }
            catch (RuntimeException error) { log.warn("Inspection dispatch failed; type={}", error.getClass().getSimpleName()); }
            finally { busy.set(false); }
        });
    }

    private TransactionTemplate transaction(boolean repeatable) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(repeatable ? 120 : 30);
        if (repeatable) transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction;
    }

    private Claim progress(Claim claim, String phase, String cursor, long objects, BigDecimal bytes,
                           long references, long checked, boolean metadata, boolean inventory) {
        return transaction(false).execute(status -> {
            repository.guard(claim);
            return repository.progress(claim, phase, cursor, objects, bytes, references, checked, metadata, inventory, properties.getLeaseSeconds());
        });
    }

    private Claim advance(Claim claim, String phase, String cursor, long checked) {
        return progress(claim, phase, cursor, 0, BigDecimal.ZERO, 0, checked, false, false);
    }

    private void finish(Claim claim, String status, String error) {
        transaction(false).executeWithoutResult(tx -> { repository.guard(claim); repository.finish(claim, status, error); });
        metrics.completed(claim.run().kind(), status, claim.run().startedAt());
    }

    private boolean stopped(Claim claim) {
        if (stopping) return true; // leave the durable lease for the next process
        Run run = repository.run(claim.run().id()).orElseThrow();
        if (run.cancelRequested()) { finish(claim, "CANCELED", null); return true; }
        if (!Instant.now().isBefore(run.startedAt().plusSeconds(properties.getRunTimeoutSeconds()))) {
            finish(claim, "PARTIAL", "inspection_time_budget_exceeded"); return true;
        }
        return false;
    }

    private void permit(boolean head) {
        long next = head ? nextHead : nextList;
        long now = System.nanoTime();
        if (next > now) {
            try { TimeUnit.NANOSECONDS.sleep(next - now); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("inspection_interrupted"); }
        }
        long following = System.nanoTime() + (head ? 100_000_000L : 1_000_000_000L);
        if (head) nextHead = following; else nextList = following;
    }

    private void apply(Claim claim, Optional<Observation> evidence, FindingDetail previous) {
        if (previous != null && (evidence.isEmpty() || !Objects.equals(previous.finding().type(), evidence.get().type())
                || !Objects.equals(previous.objectKey(), evidence.get().key()))) {
            observations.resolve(claim, previous.finding().id());
        }
        evidence.ifPresent(value -> observations.observe(claim, value));
    }

    private void recheck(Claim claim, FindingDetail finding) {
        if (finding.objectKey() != null) permit(true);
        apply(claim, rules.finding(finding), finding);
    }

    private void work() {
        Optional<Claim> acquired = transaction(false).execute(tx -> repository.claim(UUID.randomUUID(), properties.getLeaseSeconds()));
        if (acquired == null || acquired.isEmpty()) return;
        Claim claim = acquired.get();
        try {
            if (stopped(claim)) return;
            if ("RECHECK".equals(claim.run().kind())) {
                var finding = repository.finding(claim.run().findingId());
                if (finding.isPresent()) recheck(claim, finding.get());
                finish(claim, "SUCCEEDED", null);
                return;
            }
            while (!stopped(claim)) {
                int pageSize = properties.getPageSize();
                switch (claim.run().phase()) {
                    case "METADATA" -> {
                        Claim captured = claim;
                        claim = transaction(true).execute(tx -> {
                            repository.guard(captured);
                            long references = repository.snapshot(captured.run().id(), properties.getSourceZone());
                            return repository.progress(captured, "INVENTORY", null, 0, BigDecimal.ZERO, references, 0,
                                    true, false, properties.getLeaseSeconds());
                        });
                    }
                    case "INVENTORY" -> {
                        permit(false);
                        var page = storage.list(claim.cursor(), pageSize);
                        if (!page.complete() && Objects.equals(page.nextToken(), claim.cursor())) {
                            throw new IllegalStateException("inspection_inventory_not_advancing");
                        }
                        Claim current = claim;
                        claim = transaction(false).execute(tx -> {
                            repository.guard(current);
                            var delta = repository.objects(current.run().id(), page.objects());
                            return repository.progress(current, page.complete() ? "REFERENCES" : "INVENTORY",
                                    page.complete() ? null : page.nextToken(), delta.objects(), delta.bytes(), 0, 0,
                                    false, page.complete(), properties.getLeaseSeconds());
                        });
                    }
                    case "REFERENCES" -> {
                        var rows = repository.references(claim.run().id(), numericCursor(claim), pageSize);
                        if (rows.isEmpty()) { claim = advance(claim, "OBJECTS", null, 0); break; }
                        long checked = 0;
                        for (Reference reference : rows) {
                            if (reference.listedBytes() != null && (reference.expectedBytes() == null
                                    || reference.expectedBytes().equals(reference.listedBytes()))) continue;
                            if (stopped(claim)) return;
                            claim = advance(claim, "REFERENCES", claim.cursor(), 0);
                            permit(true);
                            var result = rules.inspectReference(reference, claim.run().scanStartedAt());
                            if (result.inventoryGap()) {
                                Claim current = claim;
                                transaction(false).executeWithoutResult(tx -> repository.inventoryGap(current));
                                claim = advance(claim, "REFERENCES", claim.cursor(), 0);
                            }
                            apply(claim, result.evidence(), null); checked++;
                        }
                        claim = advance(claim, "REFERENCES", Long.toString(rows.getLast().id()), checked);
                    }
                    case "OBJECTS" -> {
                        if (!claim.run().inventoryComplete()) { claim = advance(claim, "SCOPES", null, 0); break; }
                        var rows = repository.unreferencedObjects(claim.run().id(), claim.cursor(), pageSize);
                        if (rows.isEmpty()) { claim = advance(claim, "SCOPES", null, 0); break; }
                        long checked = 0;
                        for (var object : rows) {
                            if (stopped(claim)) return;
                            claim = advance(claim, "OBJECTS", claim.cursor(), 0);
                            permit(true); apply(claim, rules.object(object), null); checked++;
                        }
                        claim = advance(claim, "OBJECTS", rows.getLast().key(), checked);
                    }
                    case "SCOPES" -> {
                        var rows = repository.scopes(claim.run().id(), numericCursor(claim), pageSize);
                        if (rows.isEmpty()) { claim = advance(claim, "METADATA_ISSUES", null, 0); break; }
                        for (ScopeFact scope : rows) {
                            if (scope.valid() && scope.expectedBytes() != null && scope.expectedBytes() == scope.recordedBytes()) continue;
                            if (stopped(claim)) return;
                            claim = advance(claim, "SCOPES", claim.cursor(), 0);
                            apply(claim, rules.quota(scope.scope(), scope.owner()), null);
                        }
                        claim = advance(claim, "SCOPES", Long.toString(rows.getLast().id()), rows.size());
                    }
                    case "TASKS" -> {
                        var rows = repository.tasks(claim.run().id(), numericCursor(claim), pageSize);
                        if (rows.isEmpty()) { claim = advance(claim, "REVISIT", null, 0); break; }
                        for (TaskRef task : rows) {
                            if (stopped(claim)) return;
                            claim = advance(claim, "TASKS", claim.cursor(), 0);
                            apply(claim, rules.task(task), null);
                        }
                        claim = advance(claim, "TASKS", Long.toString(rows.getLast().id()), rows.size());
                    }
                    case "METADATA_ISSUES" -> {
                        var rows = repository.metadataIssues(claim.run().id(), numericCursor(claim), pageSize);
                        if (rows.isEmpty()) { claim = advance(claim, "TASKS", null, 0); break; }
                        for (MetadataIssue issue : rows) {
                            if (stopped(claim)) return;
                            claim = advance(claim, "METADATA_ISSUES", claim.cursor(), 0);
                            apply(claim, rules.metadata(issue), null);
                        }
                        claim = advance(claim, "METADATA_ISSUES", Long.toString(rows.getLast().id()), rows.size());
                    }
                    case "REVISIT" -> {
                        var rows = findings.openAfter(claim.cursor() == null ? null : UUID.fromString(claim.cursor()), pageSize);
                        if (rows.isEmpty()) {
                            finish(claim, claim.run().inventoryComplete() ? "SUCCEEDED" : "PARTIAL", claim.run().errorKey()); return;
                        }
                        for (FindingDetail finding : rows) {
                            if (!claim.run().inventoryComplete() && isOrphanFinding(finding)) continue;
                            if (stopped(claim)) return;
                            claim = advance(claim, "REVISIT", claim.cursor(), 0);
                            recheck(claim, finding);
                        }
                        claim = advance(claim, "REVISIT", rows.getLast().finding().id().toString(), rows.size());
                    }
                    default -> throw new IllegalStateException("inspection_phase_invalid");
                }
            }
        } catch (RuntimeException error) {
            if (stopping || Thread.currentThread().isInterrupted()) return;
            String key = error instanceof StorageInventoryException ? error.getMessage() : "inspection_processing_failed";
            try { finish(claim, "FAILED", key); }
            catch (RuntimeException lost) { log.info("Inspection lease ended; runId={}", claim.run().id()); }
            log.warn("Inspection stopped; runId={}, errorKey={}", claim.run().id(), key);
        }
    }

    private long numericCursor(Claim claim) { return claim.cursor() == null ? 0 : Long.parseLong(claim.cursor()); }

    private boolean isOrphanFinding(FindingDetail finding) {
        return "ORPHAN_OBJECT".equals(finding.finding().type()) || "UNMANAGED_OBJECT".equals(finding.finding().type());
    }

    @PreDestroy void close() { stopping = true; executor.shutdownNow(); }
}
