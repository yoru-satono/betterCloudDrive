package com.betterclouddrive.service.inspection;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.InspectionException;
import com.betterclouddrive.dal.repository.InspectionFindingRepository;
import com.betterclouddrive.dal.repository.InspectionRetentionRepository;
import com.betterclouddrive.dal.repository.InspectionRecords.*;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository;
import com.betterclouddrive.dal.repository.StorageInspectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, timeout = 30)
public class StorageInspectionService {
    private final StorageInspectionRepository repository;
    private final InspectionFindingRepository findings;
    private final QuotaAccountingRepository accounting;
    private final InspectionProperties properties;
    private final InspectionRetentionRepository retention;

    @Transactional(timeout = 30)
    public int pruneDerivedData() { return retention.prune(properties.getRetentionBatchSize()); }

    public record Overview(boolean enabled, boolean accountingReady, boolean scheduled, String zone, java.time.Instant nextScheduledAt,
                           Run latestRun, Run latestComplete, List<Map<String, Object>> counts) {}
    public record Detail(Finding finding, String objectKey, List<Review> reviews, List<Repair> repairs) {}

    public Overview overview() {
        var runs = repository.runs(0, 1);
        var next = properties.isEnabled() && properties.isScheduled()
                ? org.springframework.scheduling.support.CronExpression.parse(properties.getCron()).next(
                        java.time.ZonedDateTime.now(java.time.ZoneId.of(properties.getZone()))) : null;
        return new Overview(properties.isEnabled(), accounting.ready(), properties.isScheduled(), properties.getZone(),
                next == null ? null : next.toInstant(),
                runs.isEmpty() ? null : runs.getFirst(), repository.latestComplete().orElse(null), findings.counts());
    }

    public PageResult<Run> runs(int page, int size) {
        int offset = offset(page, size);
        return PageResult.of(repository.runs(offset, size), repository.runCount(), page, size);
    }

    public Run run(UUID id) { return repository.run(id).orElseThrow(() -> new InspectionException(404, "error.inspectionNotFound")); }

    @Transactional(timeout = 30)
    public Run start(Long actor) { return enqueue("FULL", null, actor); }

    @Transactional(timeout = 30)
    public Run recheck(UUID finding, long actor) {
        var selected = detail(finding).finding();
        if (Set.of("ORPHAN_OBJECT", "UNMANAGED_OBJECT").contains(selected.type())) {
            var full = repository.latestFull().orElse(null);
            if (full == null || !full.metadataComplete() || !full.inventoryComplete()) {
                throw new InspectionException(409, "error.inspectionInventoryIncomplete");
            }
        }
        return enqueue("RECHECK", finding, actor);
    }

    private Run enqueue(String kind, UUID finding, Long actor) {
        if (!properties.isEnabled() || !accounting.ready()) throw new InspectionException(503, "error.inspectionUnavailable");
        try { return repository.enqueue(UUID.randomUUID(), kind, finding, actor); }
        catch (DuplicateKeyException error) { throw new InspectionException(409, "error.inspectionAlreadyRunning"); }
    }

    @Transactional(timeout = 30)
    public Run cancel(UUID id) { run(id); repository.requestCancel(id); return run(id); }

    public PageResult<Finding> findings(FindingFilter filter, int page, int size) {
        int offset = offset(page, size);
        optional(filter.type(), InspectionRules.TYPES);
        optional(filter.status(), Set.of("SUSPECTED", "CONFIRMED", "RESOLVED"));
        optional(filter.reviewStatus(), Set.of("OPEN", "ACKNOWLEDGED", "IGNORED"));
        optional(filter.scope(), Set.of("PERSONAL", "TEAM"));
        if (filter.ownerId() != null && (filter.ownerId() < 1 || filter.scope() == null)) {
            throw new InspectionException(400, "error.inspectionInvalidFilter");
        }
        return PageResult.of(findings.list(filter, offset, size), findings.count(filter), page, size);
    }

    public Detail detail(UUID id) {
        var detail = repository.finding(id).orElseThrow(() -> new InspectionException(404, "error.inspectionNotFound"));
        return new Detail(detail.finding(), detail.objectKey(), findings.reviews(id), findings.repairs(id));
    }

    @Transactional(timeout = 30)
    public Review review(UUID id, long actor, String action, String reason) {
        detail(id);
        if (action == null || !Set.of("ACKNOWLEDGE", "IGNORE", "NOTE").contains(action)) {
            throw new InspectionException(400, "error.inspectionInvalidAction");
        }
        reason = reason(reason);
        Review review = findings.review(UUID.randomUUID(), id, actor, action, reason);
        if (!"NOTE".equals(action)) findings.reviewStatus(id, "IGNORE".equals(action) ? "IGNORED" : "ACKNOWLEDGED");
        return review;
    }

    static String reason(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 500) {
            throw new InspectionException(400, "error.inspectionReasonRequired");
        }
        return value.trim();
    }

    private void optional(String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) throw new InspectionException(400, "error.inspectionInvalidFilter");
    }

    private int offset(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw new InspectionException(400, "error.inspectionInvalidFilter");
        return Math.multiplyExact(page - 1, size);
    }
}
