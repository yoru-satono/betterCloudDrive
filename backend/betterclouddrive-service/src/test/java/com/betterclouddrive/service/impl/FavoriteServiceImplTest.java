package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FavoriteEntity;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.repository.FavoriteRepository;
import com.betterclouddrive.dal.repository.FileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class FavoriteServiceImplTest {

    @Mock private FavoriteRepository favoriteRepository;
    @Mock private FileRepository fileRepository;
    @InjectMocks private FavoriteServiceImpl favoriteService;

    @Test
    void addFavorite_shouldSaveFavoriteRecord() {
        FileEntity file = FileEntity.builder().id(1L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(favoriteRepository.existsByUserIdAndFileId(1L, 1L)).thenReturn(false);

        favoriteService.addFavorite(1L, 1L);

        verify(favoriteRepository).save(any(FavoriteEntity.class));
    }

    @Test
    void addFavorite_shouldThrowWhenFileNotFound() {
        when(fileRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> favoriteService.addFavorite(1L, 99L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void addFavorite_shouldThrowWhenFileDeleted() {
        FileEntity file = FileEntity.builder().id(2L).isDeleted(true).build();
        when(fileRepository.findById(2L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> favoriteService.addFavorite(1L, 2L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void addFavorite_shouldBeIdempotentWhenAlreadyFavorited() {
        FileEntity file = FileEntity.builder().id(1L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(favoriteRepository.existsByUserIdAndFileId(1L, 1L)).thenReturn(true);

        favoriteService.addFavorite(1L, 1L);

        verify(favoriteRepository, never()).save(any());
    }

    @Test
    void removeFavorite_shouldDeleteByUserIdAndFileId() {
        favoriteService.removeFavorite(1L, 1L);

        verify(favoriteRepository).deleteByUserIdAndFileId(1L, 1L);
    }

    @Test
    void isFavorite_shouldReturnRepositoryStatus() {
        when(favoriteRepository.existsByUserIdAndFileId(1L, 10L)).thenReturn(true);

        assertThat(favoriteService.isFavorite(1L, 10L)).isTrue();
    }

    @Test
    void listFavorites_shouldReturnFilesInOrder() {
        FileEntity file = FileEntity.builder().id(10L).fileName("notes.txt").build();
        Page<FileEntity> favPage = new PageImpl<>(List.of(file), PageRequest.of(0, 20), 1);
        when(favoriteRepository.findFavoriteFiles(eq(1L), isNull(), any(PageRequest.class)))
                .thenReturn(favPage);

        PageResult<FileEntity> result = favoriteService.listFavorites(1L, null, 1, 20);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords().get(0).getId()).isEqualTo(10L);
    }

    @Test
    void listFavorites_shouldReturnEmptyWhenNone() {
        Page<FileEntity> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(favoriteRepository.findFavoriteFiles(eq(1L), eq("report"), any(PageRequest.class)))
                .thenReturn(emptyPage);

        PageResult<FileEntity> result = favoriteService.listFavorites(1L, " report ", 1, 20);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(0);
    }

}
