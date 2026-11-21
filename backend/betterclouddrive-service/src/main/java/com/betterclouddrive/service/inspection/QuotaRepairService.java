package com.betterclouddrive.service.inspection;

import com.betterclouddrive.common.exception.InspectionException;
import com.betterclouddrive.dal.repository.InspectionFindingRepository;
import com.betterclouddrive.dal.repository.InspectionRecords.*;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Balance;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Scope;
import com.betterclouddrive.dal.repository.StorageInspectionRepository;
import com.betterclouddrive.service.quota.QuotaAccountingService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuotaRepairService {
    private final StorageInspectionRepository inspection;
    private final InspectionFindingRepository findings;
    private final QuotaAccountingRepository accounting;
    private final QuotaAccountingService quota;
    private final InspectionMetrics metrics;

    private Balance lock(String scope, long owner) {
        accounting.limitLockWait();
        try {
            var balance = quota.lock(Scope.valueOf(scope), owner);
            if (!accounting.active(Scope.valueOf(scope), owner)) throw new InspectionException(409, "error.inspectionRepairNotEligible");
            return balance;
        }
        catch (PessimisticLockingFailureException error) { throw new InspectionException(409, "error.inspectionSpaceBusy"); }
    }

    @Transactional(timeout = 30)
    public Preview preview(UUID findingId, long actor) {
        var detail = inspection.finding(findingId).orElseThrow(() -> new InspectionException(404, "error.inspectionNotFound"));
        var finding = detail.finding();
        if (!"QUOTA_DRIFT".equals(finding.type()) || !"CONFIRMED".equals(finding.status())) {
            throw new InspectionException(409, "error.inspectionRepairNotEligible");
        }
        var balance = lock(finding.scope(), finding.ownerId());
        var fact = inspection.quotaFact(finding.scope(), finding.ownerId());
        if (!fact.valid() || fact.expectedBytes() == null) throw new InspectionException(409, "error.quotaMetadataInvalid");
        if (fact.expectedBytes() == balance.used()) throw new InspectionException(409, "error.inspectionRepairNotEligible");
        return findings.preview(UUID.randomUUID(), findingId, actor, finding.scope(), finding.ownerId(),
                balance.used(), fact.expectedBytes(), balance.revision());
    }

    @Transactional(timeout = 30)
    public Repair apply(UUID previewId, UUID idempotencyKey, long actor, String reason) {
        reason = StorageInspectionService.reason(reason);
        if (previewId == null || idempotencyKey == null) throw new InspectionException(400, "error.inspectionInvalidAction");
        var previous = findings.repairByKey(idempotencyKey);
        if (previous.isPresent()) return replay(previous.get(), previewId, actor, reason);
        Preview preview = findings.preview(previewId).orElseThrow(() -> new InspectionException(404, "error.inspectionNotFound"));
        if (preview.actorId() != actor) throw new InspectionException(409, "error.inspectionPreviewChanged");
        var balance = lock(preview.scope(), preview.ownerId());
        previous = findings.repairByKey(idempotencyKey);
        if (previous.isPresent()) return replay(previous.get(), previewId, actor, reason);
        if (!Instant.now().isBefore(preview.expiresAt()) || balance.revision() != preview.quotaRevision()
                || balance.used() != preview.recordedBytes()) throw new InspectionException(409, "error.inspectionPreviewChanged");
        var fact = inspection.quotaFact(preview.scope(), preview.ownerId());
        if (!fact.valid() || fact.expectedBytes() == null) throw new InspectionException(409, "error.quotaMetadataInvalid");
        if (fact.expectedBytes() != preview.expectedBytes()) throw new InspectionException(409, "error.inspectionPreviewChanged");
        accounting.setUsed(Scope.valueOf(preview.scope()), preview.ownerId(), fact.expectedBytes());
        Repair repair;
        try { repair = findings.repair(UUID.randomUUID(), preview, idempotencyKey, reason); }
        catch (DuplicateKeyException error) { throw new InspectionException(409, "error.inspectionPreviewChanged"); }
        findings.resolveAfterRepair(preview.findingId());
        metrics.repairedAfterCommit();
        return repair;
    }

    private Repair replay(Repair previous, UUID preview, long actor, String reason) {
        if (!previous.previewId().equals(preview) || previous.actorId() != actor || !previous.reason().equals(reason)) {
            throw new InspectionException(409, "error.inspectionPreviewChanged");
        }
        return previous;
    }
}
