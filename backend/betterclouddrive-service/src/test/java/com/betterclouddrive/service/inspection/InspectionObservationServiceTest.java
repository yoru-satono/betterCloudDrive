package com.betterclouddrive.service.inspection;

import com.betterclouddrive.dal.repository.InspectionRecords.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class InspectionObservationServiceTest {
    private static final Instant START = Instant.parse("2026-11-20T00:00:00Z");
    private static final UUID ID = UUID.randomUUID();

    private StoredFinding previous(String type, String status, String review, int observations) {
        var finding = new Finding(ID, type, "FILE", "7", null, "PERSONAL", 1L, "ERROR", status, review,
                START, START, START.plusSeconds(590), observations, 10L, null, START, ID, null);
        return new StoredFinding(new FindingDetail(finding, "fixture"), "same", START);
    }

    private Observation evidence(String type, String signature) {
        return new Observation(type, "FILE", "7", "fixture", "PERSONAL", 1L, "ERROR", signature, 10L, null, START);
    }

    @Test void frequentRechecksDoNotPostponeTheQualifiedSampleWindow() {
        var old = previous("MISSING_OBJECT", "SUSPECTED", "OPEN", 1);
        var early = InspectionObservationService.next(old, evidence("MISSING_OBJECT", "same"), START.plusSeconds(599), 600, 86400);
        assertThat(early.observations()).isEqualTo(1);
        assertThat(early.lastCountedAt()).isEqualTo(START);
        var qualified = InspectionObservationService.next(old, evidence("MISSING_OBJECT", "same"), START.plusSeconds(600), 600, 86400);
        assertThat(qualified.status()).isEqualTo("CONFIRMED");
        assertThat(qualified.observations()).isEqualTo(2);
    }

    @Test void changedEvidenceReopensAnIgnoredIssueAndRestartsItsWindow() {
        var next = InspectionObservationService.next(previous("MISSING_OBJECT", "CONFIRMED", "IGNORED", 3),
                evidence("MISSING_OBJECT", "changed"), START.plusSeconds(1000), 600, 86400);
        assertThat(next.status()).isEqualTo("SUSPECTED");
        assertThat(next.review()).isEqualTo("OPEN");
        assertThat(next.observations()).isEqualTo(1);
        assertThat(next.stableSince()).isEqualTo(START.plusSeconds(1000));
    }

    @Test void twoObservationsDoNotConfirmAYoungUnreferencedObject() {
        var next = InspectionObservationService.next(previous("ORPHAN_OBJECT", "SUSPECTED", "OPEN", 1),
                evidence("ORPHAN_OBJECT", "same"), START.plusSeconds(600), 600, 86400);
        assertThat(next.observations()).isEqualTo(2);
        assertThat(next.status()).isEqualTo("SUSPECTED");
        var aged = InspectionObservationService.next(previous("ORPHAN_OBJECT", "SUSPECTED", "OPEN", 1),
                evidence("ORPHAN_OBJECT", "same"), START.plusSeconds(86400), 600, 86400);
        assertThat(aged.status()).isEqualTo("CONFIRMED");
    }

    @Test void recurrenceAfterResolutionStartsANewEpisode() {
        var next = InspectionObservationService.next(previous("MISSING_OBJECT", "RESOLVED", "ACKNOWLEDGED", 4),
                evidence("MISSING_OBJECT", "same"), START.plusSeconds(99999), 600, 86400);
        assertThat(next.status()).isEqualTo("SUSPECTED");
        assertThat(next.observations()).isEqualTo(1);
        assertThat(next.review()).isEqualTo("OPEN");
    }

    @Test void fingerprintDoesNotConfuseSeparatorsOrNullWithEmptyValues() {
        assertThat(InspectionObservationService.fingerprint("a|b", "c"))
                .isNotEqualTo(InspectionObservationService.fingerprint("a", "b|c"));
        assertThat(InspectionObservationService.fingerprint((Object) null))
                .isNotEqualTo(InspectionObservationService.fingerprint(""));
    }

    @Test void productionConfigurationCannotBypassTheObservationWindow() {
        var properties = new InspectionProperties();
        properties.setObservationGapSeconds(0);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
