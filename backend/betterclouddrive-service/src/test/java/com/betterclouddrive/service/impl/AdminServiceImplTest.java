package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class AdminServiceImplTest {

    @Mock private UserRepository userRepository;
    @InjectMocks private AdminServiceImpl adminService;

    @Test
    void listUsers_shouldReturnPageWithoutFilter() {
        UserEntity u = UserEntity.builder().id(1L).username("alice").build();
        Page<UserEntity> page = new PageImpl<>(List.of(u), PageRequest.of(0, 10), 1);
        when(userRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        PageResult<UserEntity> result = adminService.listUsers(null, null, 1, 10);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
    }

    @Test
    void listUsers_shouldFilterByKeyword() {
        Page<UserEntity> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(userRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        PageResult<UserEntity> result = adminService.listUsers("alice", null, 1, 10);

        assertThat(result.getRecords()).isEmpty();
        verify(userRepository).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void listUsers_shouldFilterByStatus() {
        Page<UserEntity> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(userRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        adminService.listUsers(null, 1, 1, 10);

        verify(userRepository).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void updateUserStatus_shouldSetNewStatus() {
        UserEntity user = UserEntity.builder().id(1L).status(1).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);

        adminService.updateUserStatus(1L, 0);

        assertThat(user.getStatus()).isEqualTo(0);
        verify(userRepository).save(user);
    }

    @Test
    void updateUserStatus_shouldThrowWhenUserNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.updateUserStatus(99L, 0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void updateUserQuota_shouldSetNewQuota() {
        UserEntity user = UserEntity.builder().id(1L).storageQuota(10737418240L).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);

        adminService.updateUserQuota(1L, 21474836480L);

        assertThat(user.getStorageQuota()).isEqualTo(21474836480L);
        verify(userRepository).save(user);
    }

    @Test
    void getSystemStats_shouldReturnCountsAndStorage() {
        when(userRepository.count(any(Specification.class))).thenReturn(10L, 8L);
        when(userRepository.selectTotalStorageUsed()).thenReturn(5368709120L);

        Map<String, Object> stats = adminService.getSystemStats();

        assertThat(stats).containsKey("totalUsers");
        assertThat(stats).containsKey("activeUsers");
        assertThat(stats).containsKey("totalStorageUsed");
        assertThat(stats.get("totalUsers")).isEqualTo(10L);
        assertThat(stats.get("activeUsers")).isEqualTo(8L);
        assertThat(stats.get("totalStorageUsed")).isEqualTo(5368709120L);
    }
}
