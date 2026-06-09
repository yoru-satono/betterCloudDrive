package com.betterclouddrive.web.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserPrincipal {
    private Long userId;
    private String username;
    private String jti;
    private String role;
}
