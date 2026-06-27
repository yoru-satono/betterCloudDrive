package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileTagEntity;
import com.betterclouddrive.dal.entity.TagEntity;
import com.betterclouddrive.dal.repository.FileTagRepository;
import com.betterclouddrive.dal.repository.TagRepository;
import com.betterclouddrive.service.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final TagRepository tagRepository;
    private final FileTagRepository fileTagRepository;

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
    public List<TagEntity> listTags(Long userId, String keyword) {
        if (StringUtils.hasText(keyword)) {
            return tagRepository.findByUserIdAndTagNameContainingIgnoreCaseOrderByTagNameAsc(userId, keyword.trim());
        }
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
    public PageResult<FileEntity> listFilesByTag(Long userId, Long tagId, String keyword, int page, int size) {
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        Page<FileEntity> files = fileTagRepository.findTaggedFiles(
                userId, tagId, normalizedKeyword, PageRequest.of(page - 1, size));
        return PageResult.of(files.getContent(), files.getTotalElements(), page, size);
    }
}
