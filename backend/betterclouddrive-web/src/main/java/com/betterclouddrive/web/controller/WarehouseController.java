package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.service.warehouse.WarehouseReportService;
import com.betterclouddrive.service.warehouse.WarehouseReportService.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/warehouse")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class WarehouseController {
    private final WarehouseReportService reports;

    @GetMapping("/overview")
    public ApiResponse<WarehouseReportService.Overview> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue = "ALL") Scope scope, @RequestParam(required = false) Long teamId) {
        return ApiResponse.success(reports.overview(reports.range(start, end, scope, teamId)));
    }

    @GetMapping("/trends")
    public ApiResponse<WarehouseReportService.Trends> trends(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue = "ALL") Scope scope, @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) UUID generationId) {
        return ApiResponse.success(reports.trends(reports.range(start, end, scope, teamId), generationId));
    }

    @GetMapping("/teams")
    public ApiResponse<WarehouseReportService.Teams> teams(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue = "ALL") Scope scope, @RequestParam(required = false) Long teamId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID generationId) {
        return ApiResponse.success(reports.teams(reports.range(start, end, scope, teamId), page, size, generationId));
    }

    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs() { return ApiResponse.success(reports.runs()); }
}
