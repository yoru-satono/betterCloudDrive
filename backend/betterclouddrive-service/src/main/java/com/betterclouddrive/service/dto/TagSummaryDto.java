package com.betterclouddrive.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagSummaryDto {
    private Long id;
    private Long userId;
    private Long teamId;
    private String tagName;
    private String color;
    private long fileCount;
    private LocalDateTime createdAt;
}
