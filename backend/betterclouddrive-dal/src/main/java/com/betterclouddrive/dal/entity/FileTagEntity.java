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
@TableName("file_tags")
public class FileTagEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long fileId;

    private Long tagId;

    private LocalDateTime createdAt;
}
