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
@TableName("tags")
public class TagEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String tagName;

    @Builder.Default
    private String color = "#1890ff";

    private LocalDateTime createdAt;
}
