package com.betterclouddrive.service.dto;

import com.betterclouddrive.dal.entity.TeamEntity;

public record TeamView(TeamEntity team, String role) {
}
