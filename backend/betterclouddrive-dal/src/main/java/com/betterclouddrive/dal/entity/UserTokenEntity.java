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
@TableName("user_tokens")
public class UserTokenEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String jti;

    @Builder.Default
    private String tokenType = "access";

    private LocalDateTime issuedAt;

    private LocalDateTime expiresAt;

    @Builder.Default
    private Boolean isRevoked = false;
}
