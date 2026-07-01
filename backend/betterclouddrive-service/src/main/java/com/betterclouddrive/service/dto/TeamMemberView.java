package com.betterclouddrive.service.dto;

import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.UserEntity;

public record TeamMemberView(TeamMemberEntity member, UserEntity user) {
}
