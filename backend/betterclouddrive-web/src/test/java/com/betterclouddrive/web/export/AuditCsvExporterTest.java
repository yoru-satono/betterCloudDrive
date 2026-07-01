package com.betterclouddrive.web.export;

import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AuditCsvExporterTest {

    private final AuditCsvExporter exporter = new AuditCsvExporter(
            Clock.fixed(Instant.parse("2026-06-27T04:59:14Z"), ZoneId.of("Asia/Shanghai")));

    @Test
    void teamAuditResponse_shouldBuildCsvResponseWithStableFileName() {
        TeamOperationLogEntity log = TeamOperationLogEntity.builder()
                .createdAt(LocalDateTime.of(2026, 6, 27, 12, 59, 14))
                .teamId(10L)
                .actorUserId(2L)
                .action("SHARE_CREATE")
                .targetType("FILE")
                .targetId(99L)
                .result(1)
                .statusCode(200)
                .traceId("trace-1")
                .details("created \"public\" share")
                .build();

        ResponseEntity<String> response = exporter.teamAuditResponse(10L, List.of(log));

        assertThat(response.getHeaders().getContentType())
                .isEqualTo(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"team-10-audit-20260627125914.csv\"");
        assertThat(response.getBody()).isEqualTo(String.join("\n",
                "createdAt,teamId,actorUserId,action,targetType,targetId,result,statusCode,traceId,details",
                "\"2026-06-27T12:59:14\",\"10\",\"2\",\"SHARE_CREATE\",\"FILE\",\"99\",\"1\",\"200\",\"trace-1\",\"created \"\"public\"\" share\"",
                ""));
    }

    @Test
    void teamAuditCsv_shouldRenderNullsAsEmptyCells() {
        TeamOperationLogEntity log = TeamOperationLogEntity.builder()
                .teamId(10L)
                .action("READ")
                .build();

        assertThat(exporter.teamAuditCsv(List.of(log))).isEqualTo(String.join("\n",
                "createdAt,teamId,actorUserId,action,targetType,targetId,result,statusCode,traceId,details",
                ",\"10\",,\"READ\",,,,,,",
                ""));
    }
}
