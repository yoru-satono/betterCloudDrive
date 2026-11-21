package com.betterclouddrive.service.inspection;

import com.betterclouddrive.common.dto.StorageInventory.ObjectInfo;
import com.betterclouddrive.dal.repository.InspectionRecords.*;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Scope;
import com.betterclouddrive.dal.repository.StorageInspectionRepository;
import com.betterclouddrive.storage.StorageInventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static com.betterclouddrive.service.inspection.InspectionObservationService.fingerprint;

@Service
@RequiredArgsConstructor
public class InspectionRules {
    public static final Set<String> TYPES = Set.of("MISSING_OBJECT", "OBJECT_SIZE_MISMATCH", "AUXILIARY_MISSING",
            "ORPHAN_OBJECT", "UNMANAGED_OBJECT", "QUOTA_DRIFT", "QUOTA_METADATA_INVALID", "UPLOAD_STALLED", "TASK_STALLED", "METADATA_INCONSISTENCY");
    private static final Set<String> AUXILIARY = Set.of("THUMBNAIL", "ZIP_CACHE", "ZIP_TASK");
    private final StorageInspectionRepository source;
    private final QuotaAccountingRepository accounting;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final StorageInventoryService storage;
    private final InspectionProperties properties;
    private final InspectionClock clock;
    private final ActiveObjectPrefixService activeObjects;

    public record ReferenceCheck(Optional<Observation> evidence, boolean inventoryGap) {}

    public Optional<Observation> reference(Reference snapshot) { return inspectReference(snapshot, null).evidence(); }

    public ReferenceCheck inspectReference(Reference snapshot, Instant scanStarted) {
        var current = source.liveReference(snapshot.kind(), snapshot.referenceId());
        if (current.isEmpty() || !current.get().key().equals(snapshot.key())) return new ReferenceCheck(Optional.empty(), false);
        Reference reference = current.get();
        var object = storage.stat(reference.key());
        boolean gap = scanStarted != null && snapshot.listedBytes() == null && object.isPresent()
                && !object.get().modifiedAt().isAfter(scanStarted);
        String type;
        if (object.isEmpty()) type = AUXILIARY.contains(reference.kind()) ? "AUXILIARY_MISSING" : "MISSING_OBJECT";
        else if (reference.expectedBytes() != null && reference.expectedBytes() >= 0
                && reference.expectedBytes() != object.get().size()) type = "OBJECT_SIZE_MISMATCH";
        else return new ReferenceCheck(Optional.empty(), gap);
        ObjectInfo actual = object.orElse(null);
        return new ReferenceCheck(Optional.of(new Observation(type, reference.kind(), Long.toString(reference.referenceId()), reference.key(),
                reference.scope(), reference.owner(), AUXILIARY.contains(reference.kind()) ? "WARNING" : "ERROR",
                fingerprint(reference.kind(), reference.referenceId(), reference.key(), reference.expectedBytes(),
                        actual == null ? null : actual.size(), actual == null ? null : actual.etag(), actual == null ? null : actual.modifiedAt()),
                reference.expectedBytes(), actual == null ? null : actual.size(), actual == null ? null : actual.modifiedAt())), gap);
    }

    public Optional<Observation> object(ObjectInfo snapshot) {
        if (source.referenced(snapshot.key()) || source.activeUpload(snapshot.key(), properties.getSourceZone(), properties.getUploadExpireSeconds())
                || activeObjects.protects(snapshot.key())) {
            return Optional.empty();
        }
        var current = storage.stat(snapshot.key());
        if (current.isEmpty() || source.referenced(snapshot.key())) return Optional.empty();
        var object = current.get();
        if (object.modifiedAt().isAfter(clock.now().minusSeconds(properties.getOrphanGraceSeconds()))) return Optional.empty();
        boolean managed = object.key().matches("(?s)^(?:[0-9]+/[0-9]{4}/[0-9]{2}/|users/[0-9]+/|teams/[0-9]+/|uploads/|thumbnails/|folder-zips/).*");
        String type = managed ? "ORPHAN_OBJECT" : "UNMANAGED_OBJECT";
        return Optional.of(new Observation(type, "OBJECT", null, object.key(), null, null, managed ? "WARNING" : "INFO",
                fingerprint(type, object.key(), object.size(), object.etag(), object.modifiedAt()), null, object.size(), object.modifiedAt()));
    }

    public Optional<Observation> quota(String scope, long owner) {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setTimeout(30);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status -> quotaInSnapshot(scope, owner));
    }

    private Optional<Observation> quotaInSnapshot(String scope, long owner) {
        var balance = accounting.find(Scope.valueOf(scope), owner, false);
        if (balance.isEmpty()) return Optional.empty();
        var fact = source.quotaFact(scope, owner);
        if (!fact.valid()) {
            return Optional.of(new Observation("QUOTA_METADATA_INVALID", "QUOTA", Long.toString(owner), null, scope, owner, "ERROR",
                    fingerprint("QUOTA_METADATA_INVALID", fact.invalidCount(), fact.metadataSignature()), null, null, null));
        }
        if (fact.expectedBytes() == balance.get().used()) return Optional.empty();
        BigInteger delta = BigInteger.valueOf(fact.expectedBytes()).subtract(BigInteger.valueOf(balance.get().used()));
        return Optional.of(new Observation("QUOTA_DRIFT", "QUOTA", Long.toString(owner), null, scope, owner, "WARNING",
                fingerprint("QUOTA_DRIFT", delta), fact.expectedBytes(), balance.get().used(), null));
    }

    public Optional<Observation> task(TaskRef snapshot) {
        var current = source.liveTask(snapshot.kind(), snapshot.sourceId(), properties.getSourceZone());
        if (current.isEmpty()) return Optional.empty();
        TaskRef task = current.get();
        Instant now = clock.now();
        boolean stalled;
        String type;
        if ("UPLOAD".equals(task.kind())) {
            type = "UPLOAD_STALLED";
            stalled = "1".equals(task.status()) && !now.isBefore(task.createdAt()
                    .plusSeconds(properties.getUploadExpireSeconds()).plusSeconds(properties.getUploadCleanupGraceSeconds()));
        } else {
            type = "TASK_STALLED";
            Instant started = task.startedAt() == null ? task.updatedAt() : task.startedAt();
            stalled = ("PENDING".equals(task.status()) && !now.isBefore(task.createdAt().plusSeconds(properties.getPendingTaskSeconds())))
                    || ("RUNNING".equals(task.status()) && !now.isBefore(started.plusSeconds(properties.getRunningTaskSeconds())));
        }
        if (!stalled) return Optional.empty();
        return Optional.of(new Observation(type, task.kind(), task.sourceId(), null, task.scope(), task.owner(), "WARNING",
                fingerprint(type, task.sourceId(), task.status(), task.createdAt(), task.startedAt()), null, null, null));
    }

    public Optional<Observation> finding(FindingDetail detail) {
        Finding finding = detail.finding();
        if ("METADATA_INCONSISTENCY".equals(finding.type())) {
            return metadata(new MetadataIssue(0, finding.referenceKind(), Long.parseLong(finding.referenceId()), finding.scope(),
                    finding.ownerId(), finding.metadataReason(), null, ""));
        }
        return switch (finding.referenceKind()) {
            case "FILE", "VERSION", "THUMBNAIL", "ZIP_CACHE", "ZIP_TASK" -> reference(new Reference(0, finding.referenceKind(),
                    Long.parseLong(finding.referenceId()), null, detail.objectKey(), null, finding.scope(), finding.ownerId(), null));
            case "OBJECT" -> object(new ObjectInfo(detail.objectKey(), 0, "", Instant.EPOCH));
            case "QUOTA" -> quota(finding.scope(), finding.ownerId());
            case "UPLOAD", "TEAM_TASK" -> task(new TaskRef(0, finding.referenceKind(), finding.referenceId(), finding.scope(),
                    finding.ownerId(), "", Instant.EPOCH, Instant.EPOCH, null));
            default -> throw new IllegalStateException("unsupported_inspection_reference");
        };
    }

    public Optional<Observation> metadata(MetadataIssue snapshot) {
        return source.liveMetadataIssue(snapshot.kind(), snapshot.referenceId(), snapshot.reason()).map(issue ->
                new Observation("METADATA_INCONSISTENCY", issue.kind(), Long.toString(issue.referenceId()), null,
                        issue.scope(), issue.ownerId(), "ERROR", issue.signature(), null, issue.observedBytes(), null, issue.reason()));
    }
}
