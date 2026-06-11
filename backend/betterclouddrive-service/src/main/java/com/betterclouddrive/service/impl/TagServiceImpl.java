package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileTagEntity;
import com.betterclouddrive.dal.entity.TagEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FileTagRepository;
import com.betterclouddrive.dal.repository.TagRepository;
import com.betterclouddrive.service.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final TagRepository tagRepository;
    private final FileTagRepository fileTagRepository;
    private final FileRepository fileRepository;

    @Override
    @Transactional
    public TagEntity createTag(Long userId, String tagName, String color) {
        if (tagRepository.existsByUserIdAndTagName(userId, tagName)) {
            throw new BusinessException(ApiCode.CONFLICT, "Tag '" + tagName + "' already exists");
        }

        TagEntity tag = TagEntity.builder()
                .userId(userId)
                .tagName(tagName)
                .color(color != null ? color : "#1890ff")
                .createdAt(LocalDateTime.now())
                .build();
        tagRepository.save(tag);
        log.info("User {} created tag '{}' with color {}", userId, tagName, tag.getColor());
        return tag;
    }

    @Override
    public List<TagEntity> listTags(Long userId) {
        return tagRepository.findByUserIdOrderByTagNameAsc(userId);
    }

    @Override
    @Transactional
    public TagEntity updateTag(Long userId, Long tagId, String tagName, String color) {
        TagEntity tag = tagRepository.findById(tagId).orElse(null);
        if (tag == null || !tag.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Tag not found");
        }
        if (tagName != null) {
            tag.setTagName(tagName);
        }
        if (color != null) {
            tag.setColor(color);
        }
        tagRepository.save(tag);
        log.info("User {} updated tag {} (name={}, color={})", userId, tagId, tagName, color);
        return tag;
    }

    @Override
    @Transactional
    public void deleteTag(Long userId, Long tagId) {
        TagEntity tag = tagRepository.findById(tagId).orElse(null);
        if (tag == null || !tag.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Tag not found");
        }
        fileTagRepository.deleteByTagId(tagId);
        tagRepository.deleteById(tagId);
        log.info("User {} deleted tag {} ({})", userId, tagId, tag.getTagName());
    }

    @Override
    @Transactional
    public void tagFiles(Long userId, Long tagId, List<Long> fileIds) {
        TagEntity tag = tagRepository.findById(tagId).orElse(null);
        if (tag == null || !tag.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Tag not found");
        }

        for (Long fileId : fileIds) {
            if (!fileTagRepository.existsByFileIdAndTagId(fileId, tagId)) {
                FileTagEntity ft = FileTagEntity.builder()
                        .userId(userId)
                        .fileId(fileId)
                        .tagId(tagId)
                        .createdAt(LocalDateTime.now())
                        .build();
                fileTagRepository.save(ft);
            }
        }
        log.info("User {} tagged {} files with tag {}", userId, fileIds.size(), tagId);
    }

    @Override
    @Transactional
    public void untagFile(Long userId, Long tagId, Long fileId) {
        fileTagRepository.findByFileIdAndTagId(fileId, tagId)
                .ifPresent(ft -> fileTagRepository.deleteById(ft.getId()));
        log.info("User {} removed tag {} from file {}", userId, tagId, fileId);
    }

    @Override
    public PageResult<FileEntity> listFilesByTag(Long userId, Long tagId, int page, int size) {
        Page<FileTagEntity> ftPage = fileTagRepository.findByTagIdOrderByCreatedAtDesc(
                tagId, PageRequest.of(page - 1, size));

        if (ftPage.getContent().isEmpty()) {
            return PageResult.of(List.of(), 0L, page, size);
        }

        List<Long> fileIds = ftPage.getContent().stream()
                .map(FileTagEntity::getFileId)
                .collect(Collectors.toList());

        List<FileEntity> files = fileRepository.findAllById(fileIds);
        return PageResult.of(files, ftPage.getTotalElements(), page, size);
    }
}
