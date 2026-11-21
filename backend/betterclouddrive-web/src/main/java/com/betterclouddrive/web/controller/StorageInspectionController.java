package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.repository.InspectionRecords.*;
import com.betterclouddrive.service.inspection.QuotaRepairService;
import com.betterclouddrive.service.inspection.StorageInspectionService;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/storage-inspections")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class StorageInspectionController {
    private final StorageInspectionService inspection;
    private final QuotaRepairService repairs;
    public record ReviewRequest(String action, String reason) {}
    public record RepairRequest(UUID previewId, UUID idempotencyKey, String reason) {}

    @GetMapping("/overview")
    public ApiResponse<StorageInspectionService.Overview> overview() { return ApiResponse.success(inspection.overview()); }
    @GetMapping("/runs")
    public ApiResponse<PageResult<Run>> runs(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(inspection.runs(page, size));
    }
    @PostMapping("/runs")
    public ApiResponse<Run> start(@CurrentUser UserPrincipal user) { return ApiResponse.success(inspection.start(user.getUserId())); }
    @GetMapping("/runs/{id}")
    public ApiResponse<Run> run(@PathVariable UUID id) { return ApiResponse.success(inspection.run(id)); }
    @PostMapping("/runs/{id}/cancel")
    public ApiResponse<Run> cancel(@PathVariable UUID id) { return ApiResponse.success(inspection.cancel(id)); }
    @GetMapping("/findings")
    public ApiResponse<PageResult<Finding>> findings(@RequestParam(required=false) String type, @RequestParam(required=false) String status,
            @RequestParam(required=false) String reviewStatus, @RequestParam(required=false) String scope, @RequestParam(required=false) Long ownerId,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(inspection.findings(new FindingFilter(type, status, reviewStatus, scope, ownerId), page, size));
    }
    @GetMapping("/findings/{id}")
    public ApiResponse<StorageInspectionService.Detail> detail(@PathVariable UUID id) { return ApiResponse.success(inspection.detail(id)); }
    @PostMapping("/findings/{id}/rechecks")
    public ApiResponse<Run> recheck(@PathVariable UUID id, @CurrentUser UserPrincipal user) {
        return ApiResponse.success(inspection.recheck(id, user.getUserId()));
    }
    @PostMapping("/findings/{id}/reviews")
    public ApiResponse<Review> review(@PathVariable UUID id, @CurrentUser UserPrincipal user, @RequestBody ReviewRequest request) {
        return ApiResponse.success(inspection.review(id, user.getUserId(), request.action(), request.reason()));
    }
    @PostMapping("/findings/{id}/repair-preview")
    public ApiResponse<Preview> preview(@PathVariable UUID id, @CurrentUser UserPrincipal user) {
        return ApiResponse.success(repairs.preview(id, user.getUserId()));
    }
    @PostMapping("/quota-repairs")
    public ApiResponse<Repair> repair(@CurrentUser UserPrincipal user, @RequestBody RepairRequest request) {
        return ApiResponse.success(repairs.apply(request.previewId(), request.idempotencyKey(), user.getUserId(), request.reason()));
    }
}
