package com.betterclouddrive.service.dto;

public record ShareUpdateCommand(
        String password,
        Long expireAtMs,
        Integer maxVisits,
        boolean clearPassword,
        boolean clearExpireAt,
        boolean clearMaxVisits) {

    public static ShareUpdateCommand legacy(String password, Long expireAtMs, Integer maxVisits) {
        return new ShareUpdateCommand(password, expireAtMs, maxVisits, false, false, false);
    }
}
