package com.betterclouddrive.dal.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Only Run and Finding are list-safe; object keys are restricted to detail and worker records. */
public final class InspectionRecords {
    private InspectionRecords() {}
    public record Run(UUID id, String kind, UUID findingId, String status, String phase, int ruleVersion,
                      Long requestedBy, Instant createdAt, Instant startedAt, Instant finishedAt,
                      Instant metadataAt, Instant scanStartedAt, Instant scanFinishedAt,
                      boolean metadataComplete, boolean inventoryComplete, boolean cancelRequested,
                      long objectsScanned, BigDecimal objectBytes, long referencesScanned,
                      long candidatesChecked, String errorKey) {}
    public record Claim(Run run, UUID token, String cursor) {}
    public record Reference(long id, String kind, long referenceId, Long fileId, String key,
                            Long expectedBytes, String scope, long owner, Long listedBytes) {}
    public record ScopeFact(long id, String scope, long owner, long recordedBytes, long revision,
                            Long expectedBytes, boolean valid, long invalidCount) {}
    public record QuotaFact(Long expectedBytes, boolean valid, long invalidCount, String metadataSignature) {}
    public record MetadataIssue(long id, String kind, long referenceId, String scope, long ownerId,
                                String reason, Long observedBytes, String signature) {}
    public record Finding(UUID id, String type, String referenceKind, String referenceId, String objectFingerprint,
                          String scope, Long ownerId, String severity, String status, String reviewStatus,
                          Instant firstSeenAt, Instant stableSince, Instant lastObservedAt, int observations,
                          Long expectedBytes, Long observedBytes, Instant objectModifiedAt, UUID lastSeenRun,
                          Instant resolvedAt, String metadataReason) {
        public Finding(UUID id, String type, String referenceKind, String referenceId, String objectFingerprint,
                       String scope, Long ownerId, String severity, String status, String reviewStatus,
                       Instant firstSeenAt, Instant stableSince, Instant lastObservedAt, int observations,
                       Long expectedBytes, Long observedBytes, Instant objectModifiedAt, UUID lastSeenRun, Instant resolvedAt) {
            this(id, type, referenceKind, referenceId, objectFingerprint, scope, ownerId, severity, status, reviewStatus,
                    firstSeenAt, stableSince, lastObservedAt, observations, expectedBytes, observedBytes, objectModifiedAt, lastSeenRun, resolvedAt, null);
        }
    }
    public record FindingDetail(Finding finding, String objectKey) {}
    public record StoredFinding(FindingDetail detail, String signature, Instant lastCountedAt) {}
    public record TaskRef(long id, String kind, String sourceId, String scope, long owner, String status,
                          Instant createdAt, Instant updatedAt, Instant startedAt) {}
    public record FindingFilter(String type, String status, String reviewStatus, String scope, Long ownerId) {}
    public record Review(UUID id, UUID findingId, long actorId, String action, String reason, Instant createdAt) {}
    public record Preview(UUID id, UUID findingId, long actorId, String scope, long ownerId, long recordedBytes,
                          long expectedBytes, long quotaRevision, Instant createdAt, Instant expiresAt) {}
    public record Repair(UUID id, UUID previewId, UUID findingId, long actorId, UUID idempotencyKey, String scope,
                         long ownerId, long usedBefore, long usedAfter, long quotaRevisionBefore, String reason,
                         int ruleVersion, Instant createdAt) {}
    public record Observation(String type, String referenceKind, String referenceId, String key, String scope,
                              Long owner, String severity, String signature, Long expected, Long observed,
                              Instant modifiedAt, String metadataReason) {
        public Observation(String type, String referenceKind, String referenceId, String key, String scope, Long owner,
                           String severity, String signature, Long expected, Long observed, Instant modifiedAt) {
            this(type, referenceKind, referenceId, key, scope, owner, severity, signature, expected, observed, modifiedAt, null);
        }
    }
}
