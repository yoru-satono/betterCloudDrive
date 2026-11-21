package com.betterclouddrive.service.inspection;

import com.betterclouddrive.dal.repository.InspectionFindingRepository;
import com.betterclouddrive.dal.repository.InspectionRecords.*;
import com.betterclouddrive.dal.repository.StorageInspectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InspectionObservationService {
    private final StorageInspectionRepository runs;
    private final InspectionFindingRepository findings;
    private final InspectionProperties properties;
    private final InspectionClock clock;

    public record Episode(String status, String review, int observations, Instant stableSince, Instant lastCountedAt) {}

    /** Frequent rechecks do not move the last qualified sample's timestamp. */
    public static Episode next(StoredFinding previous, Observation evidence, Instant now, long gapSeconds, long orphanGraceSeconds) {
        Finding finding = previous.detail().finding();
        if ("RESOLVED".equals(finding.status()) || !previous.signature().equals(evidence.signature())) {
            return new Episode("SUSPECTED", "OPEN", 1, now, now);
        }
        boolean qualifies = !now.isBefore(previous.lastCountedAt().plusSeconds(gapSeconds));
        int observations = finding.observations() + (qualifies ? 1 : 0);
        boolean aged = !"ORPHAN_OBJECT".equals(evidence.type()) || (evidence.modifiedAt() != null
                && !now.isBefore(evidence.modifiedAt().plusSeconds(orphanGraceSeconds)));
        String status = observations >= 2 && aged ? "CONFIRMED" : "SUSPECTED";
        return new Episode(status, finding.reviewStatus(), observations, finding.stableSince(),
                qualifies ? now : previous.lastCountedAt());
    }

    @Transactional(timeout = 30)
    public UUID observe(Claim claim, Observation evidence) {
        runs.guard(claim);
        Instant now = clock.now();
        String identity = fingerprint(evidence.type(), evidence.referenceKind(), evidence.referenceId(), evidence.key(),
                evidence.scope(), evidence.owner(), evidence.metadataReason());
        var previous = findings.lockIdentity(identity);
        if (previous.isEmpty()) {
            UUID id = UUID.randomUUID();
            findings.insert(id, claim.run().id(), identity, evidence.key() == null ? null : fingerprint(evidence.key()), evidence, now);
            return id;
        }
        var old = previous.get();
        var episode = next(old, evidence, now, properties.getObservationGapSeconds(), properties.getOrphanGraceSeconds());
        UUID id = old.detail().finding().id();
        findings.update(id, claim.run().id(), evidence, episode.status(), episode.review(), episode.observations(),
                episode.stableSince(), episode.lastCountedAt(), now);
        return id;
    }

    @Transactional(timeout = 30)
    public void resolve(Claim claim, UUID finding) {
        runs.guard(claim);
        findings.resolve(finding, claim.run().id(), clock.now());
    }

    public static String fingerprint(Object... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                if (part == null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); continue; }
                byte[] bytes = part.toString().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("sha256_unavailable"); }
    }
}
