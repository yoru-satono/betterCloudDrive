package com.betterclouddrive.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("operation_logs")
public class OperationLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String actionType;
    private String targetType;
    private Long targetId;
    private String detail;
    private String ipAddress;
    private String userAgent;
    private Integer result;
    private Integer durationMs;
    private LocalDateTime createdAt;
}
