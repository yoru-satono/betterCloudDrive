package com.betterclouddrive.web.dto.request;

import com.betterclouddrive.service.dto.ShareUpdateCommand;
import lombok.Data;

@Data
public class UpdateShareRequest {
    private String password;    // set to "" to remove password
    private Long expireAt;      // set to 0 to remove expiry
    private Integer maxVisits;
    private Boolean clearPassword;
    private Boolean clearExpireAt;
    private Boolean clearMaxVisits;

    public ShareUpdateCommand toCommand() {
        return new ShareUpdateCommand(
                password,
                expireAt,
                maxVisits,
                Boolean.TRUE.equals(clearPassword),
                Boolean.TRUE.equals(clearExpireAt),
                Boolean.TRUE.equals(clearMaxVisits));
    }
}
