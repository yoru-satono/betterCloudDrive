package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateShareRequest {
    @NotNull
    private Long fileId;
    private String password;
    private Long expireAt;      // epoch ms, null = never
    private Integer maxVisits; // null = unlimited
    @Email
    private String notifyEmail; // if set, send share notification email
}
