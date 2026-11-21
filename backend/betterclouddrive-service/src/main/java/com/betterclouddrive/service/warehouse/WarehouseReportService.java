package com.betterclouddrive.service.warehouse;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class WarehouseReportService {
    public enum Scope { ALL, PERSONAL, TEAM }
    public record Range(LocalDate start, LocalDate end, Scope scope, long scopeId) {}
    public record Metadata(String timezone, LocalDate startDate, LocalDate endDate, Scope scope, long scopeId, String generationId,
                           Instant publishedAt, Instant dataThrough, LocalDate coverageStart, boolean stale, String status) {}
    public record Overview(Metadata meta, Map<String, Object> usage, Map<String, Object> capacity) {}
    public record Day(LocalDate day, Map<String, Object> usage, Map<String, Object> capacity) {}
    public record Trends(Metadata meta, List<Day> days) {}
    public record Teams(Metadata meta, PageResult<Map<String, Object>> teams) {}

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String FILTER = "generation_id = {generation:UUID} AND scope_type = {scope:String} AND scope_id = {scopeId:Int64} AND day BETWEEN {start:Date} AND {end:Date}";
    private static final List<String> FLOW_METRICS = List.of("registered_users", "operations", "uploads", "uploaded_logical_bytes",
            "instant_uploads", "download_attempts", "download_succeeded", "download_failed", "download_unknown", "download_written_bytes",
            "shares_created", "shares_canceled", "share_accesses");
    private final WarehouseReportClient client;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public WarehouseReportService(WarehouseReportClient client) { this(client, Clock.systemUTC()); }
    WarehouseReportService(WarehouseReportClient client, Clock clock) { this.client = client; this.clock = clock; }

    public Range range(LocalDate start, LocalDate end, Scope scope, Long teamId) {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        end = end == null ? today : end;
        start = start == null ? end.minusDays(29) : start;
        if (scope == null || start.isAfter(end) || end.isAfter(today)
                || start.isBefore(today.minusDays(364)) || ChronoUnit.DAYS.between(start, end) > 364
                || (teamId != null && (scope != Scope.TEAM || teamId <= 0))) {
            throw new BusinessException(ApiCode.BAD_REQUEST);
        }
        return new Range(start, end, scope, teamId == null ? 0 : teamId);
    }

    private Metadata metadata(Range range) {
        return metadata(range, null);
    }

    private Metadata metadata(Range range, UUID requestedGeneration) {
        var publication = client.query("""
            SELECT toString(generation_id) AS generation_id,
                toUnixTimestamp64Milli(published_at) AS published_at,
                toUnixTimestamp64Milli(data_through) AS data_through
            FROM publications AS publication
            """ + (requestedGeneration == null ? "" : " WHERE publication.generation_id = {requestedGeneration:UUID}")
                + " ORDER BY published_at DESC LIMIT 1", requestedGeneration == null ? Map.of() : Map.of("requestedGeneration", requestedGeneration));
        if (publication.isEmpty() && requestedGeneration != null) throw new BusinessException(ApiCode.NOT_FOUND);
        if (publication.isEmpty()) return new Metadata(ZONE.getId(), range.start(), range.end(), range.scope(), range.scopeId(), null,
                null, null, null, false, "UNINITIALIZED");
        var current = publication.getFirst();
        String generation = current.get("generation_id").toString();
        var coverage = client.query("SELECT min(day) AS day, count() AS count FROM ads_capacity_daily WHERE generation_id = {generation:UUID}",
                Map.of("generation", generation)).getFirst();
        LocalDate coverageStart = ((Number) coverage.get("count")).longValue() == 0 ? null : LocalDate.parse(coverage.get("day").toString());
        Instant publishedAt = instant(current.get("published_at"));
        return new Metadata(ZONE.getId(), range.start(), range.end(), range.scope(), range.scopeId(), generation, publishedAt,
                instant(current.get("data_through")), coverageStart,
                Duration.between(publishedAt, clock.instant()).compareTo(Duration.ofHours(2)) > 0, "READY");
    }

    private Map<String, Object> parameters(Range range, Metadata meta) {
        return new HashMap<>(Map.of("generation", meta.generationId(), "start", range.start(), "end", range.end(),
                "scope", range.scope().name(), "scopeId", range.scopeId()));
    }

    public Overview overview(Range range) {
        Metadata meta = metadata(range);
        if (meta.generationId() == null) return new Overview(meta, Map.of(), Map.of());
        var parameters = parameters(range, meta);
        LocalDate activeDay = meta.dataThrough().atZone(ZONE).toLocalDate();
        if (activeDay.isAfter(range.end())) activeDay = range.end();
        parameters.put("activeDay", activeDay);
        String sums = FLOW_METRICS.stream().map(field -> "sum(" + field + ") AS " + field).collect(Collectors.joining(", "));
        var usage = new LinkedHashMap<>(client.query("SELECT " + sums + ", count() AS observed_days, sumIf(active_users, day = {activeDay:Date}) AS active_users, "
                + "sumIf(active_teams, day = {activeDay:Date}) AS active_teams FROM ads_usage_daily WHERE " + FILTER, parameters).getFirst());
        long observedDays = ((Number) usage.remove("observed_days")).longValue();
        boolean covered = meta.coverageStart() != null && !range.end().isBefore(meta.coverageStart())
                && !range.start().isAfter(meta.dataThrough().atZone(ZONE).toLocalDate());
        if (observedDays == 0 && !covered) usage.clear();
        else usage.put("active_day", activeDay.toString());
        var capacity = client.query("SELECT * EXCEPT (generation_id, sampled_at), toUnixTimestamp64Milli(sampled_at) AS sampled_at FROM ads_capacity_daily WHERE " + FILTER + " ORDER BY day DESC LIMIT 1", parameters);
        return new Overview(meta, usage, capacity.isEmpty() ? Map.of() : capacity.getFirst());
    }

    public Trends trends(Range range) {
        return trends(range, null);
    }

    public Trends trends(Range range, UUID generationId) {
        Metadata meta = metadata(range, generationId);
        if (meta.generationId() == null) return new Trends(meta, List.of());
        var parameters = parameters(range, meta);
        var usage = byDay(client.query("SELECT * EXCEPT generation_id FROM ads_usage_daily WHERE " + FILTER + " ORDER BY day", parameters));
        var capacity = byDay(client.query("SELECT * EXCEPT (generation_id, sampled_at), toUnixTimestamp64Milli(sampled_at) AS sampled_at FROM ads_capacity_daily WHERE " + FILTER + " ORDER BY day", parameters));
        var days = new ArrayList<Day>();
        LocalDate coveredThrough = meta.dataThrough().atZone(ZONE).toLocalDate();
        for (LocalDate day = range.start(); !day.isAfter(range.end()); day = day.plusDays(1)) {
            Map<String, Object> activity = usage.get(day);
            if (activity == null && meta.coverageStart() != null && !day.isBefore(meta.coverageStart()) && !day.isAfter(coveredThrough)) {
                var zero = new LinkedHashMap<String, Object>();
                FLOW_METRICS.forEach(field -> zero.put(field, 0L));
                zero.put("active_users", 0L);
                zero.put("active_teams", 0L);
                activity = zero;
            }
            days.add(new Day(day, activity, capacity.get(day)));
        }
        return new Trends(meta, days);
    }

    public Teams teams(Range range, int page, int size) {
        return teams(range, page, size, null);
    }

    public Teams teams(Range range, int page, int size, UUID generationId) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw new BusinessException(ApiCode.BAD_REQUEST);
        Metadata meta = metadata(range, generationId);
        if (meta.generationId() == null) return new Teams(meta, PageResult.of(List.of(), 0, page, size));
        var parameters = parameters(range, meta);
        parameters.put("offset", (page - 1L) * size);
        parameters.put("limit", size);
        String teamFilter = "generation_id = {generation:UUID} AND scope_type = 'TEAM' AND scope_id > 0 AND day BETWEEN {start:Date} AND {end:Date}"
                + (range.scopeId() > 0 ? " AND scope_id = {scopeId:Int64}" : "");
        String grouped = "SELECT scope_id AS team_id, count() AS samples, argMax(current_bytes + trash_bytes + version_bytes, day) AS logical_bytes, "
                + "argMax(file_count, day) AS file_count, argMax(member_count, day) AS member_count, max(day) AS sampled_day "
                + "FROM ads_capacity_daily WHERE " + teamFilter + " GROUP BY scope_id";
        if (range.scope() == Scope.PERSONAL) return new Teams(meta, PageResult.of(List.of(), 0, page, size));
        String identities = "SELECT scope_id AS team_id FROM ads_capacity_daily WHERE " + teamFilter
                + " UNION DISTINCT SELECT scope_id AS team_id FROM ads_usage_daily WHERE " + teamFilter;
        long total = ((Number) client.query("SELECT count() AS count FROM (" + identities + ")", parameters).getFirst().get("count")).longValue();
        LocalDate activeDay = meta.dataThrough().atZone(ZONE).toLocalDate();
        parameters.put("activeDay", activeDay.isAfter(range.end()) ? range.end() : activeDay);
        String activity = "SELECT scope_id AS team_id, sum(uploads) AS uploads, sum(uploaded_logical_bytes) AS uploaded_logical_bytes, "
                + "sum(download_attempts) AS download_attempts, sum(download_written_bytes) AS download_written_bytes, "
                + "sumIf(active_users, day = {activeDay:Date}) AS active_users FROM ads_usage_daily WHERE " + teamFilter + " GROUP BY scope_id";
        var rows = client.query("SELECT identity.team_id AS team_id, "
                + "if(capacity.samples = 0, NULL, capacity.logical_bytes) AS logical_bytes, "
                + "if(capacity.samples = 0, NULL, capacity.file_count) AS file_count, "
                + "if(capacity.samples = 0, NULL, capacity.member_count) AS member_count, "
                + "if(capacity.samples = 0, NULL, capacity.sampled_day) AS sampled_day, "
                + "activity.uploads AS uploads, activity.uploaded_logical_bytes AS uploaded_logical_bytes, "
                + "activity.download_attempts AS download_attempts, activity.download_written_bytes AS download_written_bytes, "
                + "activity.active_users AS active_users FROM (" + identities + ") AS identity "
                + "LEFT JOIN (" + grouped + ") AS capacity ON identity.team_id = capacity.team_id "
                + "LEFT JOIN (" + activity + ") AS activity ON identity.team_id = activity.team_id "
                + "ORDER BY logical_bytes DESC NULLS LAST, team_id LIMIT {limit:UInt32} OFFSET {offset:UInt64}", parameters);
        return new Teams(meta, PageResult.of(rows, total, page, size));
    }

    public List<Map<String, Object>> runs() {
        return client.query("""
            WITH latest AS (
                SELECT run_id, argMax(tuple(job, status, started_at, error_key), updated_at) AS fact,
                    max(updated_at) AS last_update FROM pipeline_runs GROUP BY run_id
            )
            SELECT toString(run_id) AS run_id, fact.1 AS job, fact.2 AS status,
                toUnixTimestamp64Milli(fact.3) AS started_at,
                toUnixTimestamp64Milli(last_update) AS updated_at, fact.4 AS error_key
            FROM latest ORDER BY updated_at DESC LIMIT 50
            """, Map.of());
    }

    private Map<LocalDate, Map<String, Object>> byDay(List<Map<String, Object>> rows) {
        return rows.stream().collect(Collectors.toMap(row -> LocalDate.parse(row.get("day").toString()), row -> row));
    }
    private static Instant instant(Object value) { return Instant.ofEpochMilli(((Number) value).longValue()); }
}
