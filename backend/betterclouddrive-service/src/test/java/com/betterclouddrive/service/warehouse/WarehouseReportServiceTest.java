package com.betterclouddrive.service.warehouse;

import com.betterclouddrive.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.betterclouddrive.service.warehouse.WarehouseReportService.Scope;

class WarehouseReportServiceTest {
    private final WarehouseReportClient client = mock(WarehouseReportClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-19T16:30:00Z"), ZoneOffset.UTC);
    private final WarehouseReportService service = new WarehouseReportService(client, clock);

    @Test void defaultsUseBusinessTimezoneAndInvalidScopesNeverReachClickhouse() {
        var range = service.range(null, null, Scope.ALL, null);
        assertThat(range.end()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThatThrownBy(() -> service.range(null, null, Scope.PERSONAL, 1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.range(null, LocalDate.of(2026, 11, 21), Scope.ALL, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.range(LocalDate.of(2025, 11, 19), null, Scope.ALL, null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(client);
    }

    @Test void firstRunDoesNotQueryUnbuiltModelsOrInventZeroHistory() {
        when(client.query(anyString(), anyMap())).thenReturn(List.of());
        var range = service.range(null, null, Scope.ALL, null);
        var result = service.overview(range);
        assertThat(result.meta().status()).isEqualTo("UNINITIALIZED");
        assertThat(result.usage()).isEmpty();
        assertThat(result.capacity()).isEmpty();
        verify(client, times(1)).query(anyString(), anyMap());
    }

    @Test void missingDatesBeforeCoverageRemainNullAndCoveredZeroDaysAreExplicit() {
        var publishedAt = clock.instant().minusSeconds(3600).toEpochMilli();
        when(client.query(contains("FROM publications"), anyMap())).thenReturn(List.of(Map.of(
                "generation_id", UUID.randomUUID().toString(), "published_at", publishedAt, "data_through", publishedAt)));
        when(client.query(contains("min(day)"), anyMap())).thenReturn(List.of(Map.of("day", "2026-11-19", "count", 1)));
        when(client.query(startsWith("SELECT * EXCEPT"), anyMap())).thenReturn(List.of());
        var trends = service.trends(service.range(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 20), Scope.ALL, null));
        assertThat(trends.days()).hasSize(3);
        assertThat(trends.days().get(0).usage()).isNull();
        assertThat(trends.days().get(1).usage()).containsEntry("uploads", 0L);
        assertThat(trends.days().get(2).usage()).isNull();
        assertThat(trends.days()).allSatisfy(day -> assertThat(day.capacity()).isNull());
    }

    @Test void overviewDistinguishesUncollectedDatesFromCoveredZeroUsage() {
        long collected = Instant.parse("2026-11-19T12:00:00Z").toEpochMilli();
        when(client.query(contains("FROM publications"), anyMap())).thenReturn(List.of(Map.of(
                "generation_id", UUID.randomUUID().toString(), "published_at", collected, "data_through", collected)));
        when(client.query(contains("min(day)"), anyMap())).thenReturn(List.of(Map.of("day", "2026-11-19", "count", 1)));
        when(client.query(startsWith("SELECT sum("), anyMap())).thenReturn(List.of(Map.of("observed_days", 0L, "uploads", 0L)));
        when(client.query(startsWith("SELECT * EXCEPT"), anyMap())).thenReturn(List.of());
        for (LocalDate missing : List.of(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 20))) {
            var report = service.overview(service.range(missing, missing, Scope.ALL, null));
            assertThat(report.usage()).isEmpty();
            assertThat(report.capacity()).isEmpty();
        }
        LocalDate covered = LocalDate.of(2026, 11, 19);
        assertThat(service.overview(service.range(covered, covered, Scope.ALL, null)).usage())
                .containsEntry("uploads", 0L).doesNotContainKey("observed_days");
    }
}
