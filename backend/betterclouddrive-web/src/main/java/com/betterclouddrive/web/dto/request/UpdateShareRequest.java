package com.betterclouddrive.web.dto.request;

import lombok.Data;

@Data
public class UpdateShareRequest {
    private String password;    // set to "" to remove password
    private Long expireAt;      // set to 0 to remove expiry
    private Integer maxVisits;
}
