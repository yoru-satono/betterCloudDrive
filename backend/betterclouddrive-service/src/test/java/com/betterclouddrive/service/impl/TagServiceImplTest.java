package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileTagEntity;
import com.betterclouddrive.dal.entity.TagEntity;
import com.betterclouddrive.dal.repository.FileTagRepository;
import com.betterclouddrive.dal.repository.TagRepository;
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
class TagServiceImplTest {

    @Mock private TagRepository tagRepository;
    @Mock private FileTagRepository fileTagRepository;
    @InjectMocks private TagServiceImpl tagService;

    private TagEntity tag(Long id, Long userId) {
        return TagEntity.builder().id(id).userId(userId).tagName("work").color("#1890ff").build();
    }

    @Test
    void createTag_shouldSaveTag() {
        when(tagRepository.existsByUserIdAndTagName(1L, "work")).thenReturn(false);
        when(tagRepository.save(any(TagEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TagEntity result = tagService.createTag(1L, "work", "#ff0000");

        assertThat(result.getTagName()).isEqualTo("work");
        assertThat(result.getColor()).isEqualTo("#ff0000");
        verify(tagRepository).save(any(TagEntity.class));
    }

    @Test
    void createTag_shouldUseDefaultColorWhenNull() {
        when(tagRepository.existsByUserIdAndTagName(1L, "personal")).thenReturn(false);
        when(tagRepository.save(any(TagEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TagEntity result = tagService.createTag(1L, "personal", null);

        assertThat(result.getColor()).isEqualTo("#1890ff");
    }

    @Test
    void createTag_shouldThrowWhenNameAlreadyExists() {
        when(tagRepository.existsByUserIdAndTagName(1L, "work")).thenReturn(true);

        assertThatThrownBy(() -> tagService.createTag(1L, "work", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void updateTag_shouldUpdateNameAndColor() {
        TagEntity tag = tag(1L, 1L);
        when(tagRepository.findById(1L)).thenReturn(Optional.of(tag));
        when(tagRepository.save(any())).thenReturn(tag);

        tagService.updateTag(1L, 1L, "updated", "#aabbcc");

        assertThat(tag.getTagName()).isEqualTo("updated");
        assertThat(tag.getColor()).isEqualTo("#aabbcc");
    }

    @Test
    void updateTag_shouldThrowWhenTagNotOwned() {
        when(tagRepository.findById(1L)).thenReturn(Optional.of(tag(1L, 2L)));

        assertThatThrownBy(() -> tagService.updateTag(1L, 1L, "x", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteTag_shouldDeleteTagAndAllAssociations() {
        TagEntity tag = tag(1L, 1L);
        when(tagRepository.findById(1L)).thenReturn(Optional.of(tag));

        tagService.deleteTag(1L, 1L);

        verify(fileTagRepository).deleteByTagId(1L);
        verify(tagRepository).deleteById(1L);
    }

    @Test
    void tagFiles_shouldCreateFileTagRecords() {
        when(tagRepository.findById(1L)).thenReturn(Optional.of(tag(1L, 1L)));
        when(fileTagRepository.existsByFileIdAndTagId(10L, 1L)).thenReturn(false);
        when(fileTagRepository.existsByFileIdAndTagId(11L, 1L)).thenReturn(false);

        tagService.tagFiles(1L, 1L, List.of(10L, 11L));

        verify(fileTagRepository, times(2)).save(any(FileTagEntity.class));
    }

    @Test
    void tagFiles_shouldSkipAlreadyTaggedFiles() {
        when(tagRepository.findById(1L)).thenReturn(Optional.of(tag(1L, 1L)));
        when(fileTagRepository.existsByFileIdAndTagId(10L, 1L)).thenReturn(true);

        tagService.tagFiles(1L, 1L, List.of(10L));

        verify(fileTagRepository, never()).save(any());
    }

    @Test
    void untagFile_shouldDeleteAssociation() {
        FileTagEntity ft = FileTagEntity.builder().id(99L).fileId(10L).tagId(1L).build();
        when(fileTagRepository.findByFileIdAndTagId(10L, 1L)).thenReturn(Optional.of(ft));

        tagService.untagFile(1L, 1L, 10L);

        verify(fileTagRepository).deleteById(99L);
    }

    @Test
    void listFilesByTag_shouldReturnFiles() {
        FileEntity file = FileEntity.builder().id(10L).build();
        Page<FileEntity> files = new PageImpl<>(List.of(file), PageRequest.of(0, 20), 1);
        when(fileTagRepository.findTaggedFiles(eq(1L), eq(1L), eq("report"), any(PageRequest.class)))
                .thenReturn(files);

        PageResult<FileEntity> result = tagService.listFilesByTag(1L, 1L, " report ", 1, 20);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
    }
}
