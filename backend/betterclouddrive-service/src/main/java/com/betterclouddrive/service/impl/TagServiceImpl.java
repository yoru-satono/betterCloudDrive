package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileTagEntity;
import com.betterclouddrive.dal.entity.TagEntity;
import com.betterclouddrive.dal.mapper.FileMapper;
import com.betterclouddrive.dal.mapper.FileTagMapper;
import com.betterclouddrive.dal.mapper.TagMapper;
import com.betterclouddrive.service.TagService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final TagMapper tagMapper;
    private final FileTagMapper fileTagMapper;
    private final FileMapper fileMapper;

    @Override
    @Transactional
    public TagEntity createTag(Long userId, String tagName, String color) {
        // Check unique tag name per user
        Long count = tagMapper.selectCount(new LambdaQueryWrapper<TagEntity>()
                .eq(TagEntity::getUserId, userId)
                .eq(TagEntity::getTagName, tagName));
        if (count > 0) {
            throw new BusinessException(ApiCode.CONFLICT, "Tag '" + tagName + "' already exists");
        }

        TagEntity tag = TagEntity.builder()
                .userId(userId)
                .tagName(tagName)
                .color(color != null ? color : "#1890ff")
                .createdAt(LocalDateTime.now())
                .build();
        tagMapper.insert(tag);
        log.info("User {} created tag '{}' with color {}", userId, tagName, tag.getColor());
        return tag;
    }

    @Override
    public List<TagEntity> listTags(Long userId) {
        return tagMapper.selectList(new LambdaQueryWrapper<TagEntity>()
                .eq(TagEntity::getUserId, userId)
                .orderByAsc(TagEntity::getTagName));
    }

    @Override
    @Transactional
    public TagEntity updateTag(Long userId, Long tagId, String tagName, String color) {
        TagEntity tag = tagMapper.selectById(tagId);
        if (tag == null || !tag.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Tag not found");
        }
        if (tagName != null) {
            tag.setTagName(tagName);
        }
        if (color != null) {
            tag.setColor(color);
        }
        tagMapper.updateById(tag);
        log.info("User {} updated tag {} (name={}, color={})", userId, tagId, tagName, color);
        return tag;
    }

    @Override
    @Transactional
    public void deleteTag(Long userId, Long tagId) {
        TagEntity tag = tagMapper.selectById(tagId);
        if (tag == null || !tag.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Tag not found");
        }
        // Delete all file-tag associations for this tag
        fileTagMapper.delete(new LambdaQueryWrapper<FileTagEntity>()
                .eq(FileTagEntity::getTagId, tagId));
        tagMapper.deleteById(tagId);
        log.info("User {} deleted tag {} ({})", userId, tagId, tag.getTagName());
    }

    @Override
    @Transactional
    public void tagFiles(Long userId, Long tagId, List<Long> fileIds) {
        TagEntity tag = tagMapper.selectById(tagId);
        if (tag == null || !tag.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Tag not found");
        }

        for (Long fileId : fileIds) {
            // Skip if already tagged
            Long count = fileTagMapper.selectCount(new LambdaQueryWrapper<FileTagEntity>()
                    .eq(FileTagEntity::getFileId, fileId)
                    .eq(FileTagEntity::getTagId, tagId));
            if (count == 0) {
                FileTagEntity ft = FileTagEntity.builder()
                        .userId(userId)
                        .fileId(fileId)
                        .tagId(tagId)
                        .createdAt(LocalDateTime.now())
                        .build();
                fileTagMapper.insert(ft);
            }
        }
        log.info("User {} tagged {} files with tag {}", userId, fileIds.size(), tagId);
    }

    @Override
    @Transactional
    public void untagFile(Long userId, Long tagId, Long fileId) {
        fileTagMapper.delete(new LambdaQueryWrapper<FileTagEntity>()
                .eq(FileTagEntity::getFileId, fileId)
                .eq(FileTagEntity::getTagId, tagId));
        log.info("User {} removed tag {} from file {}", userId, tagId, fileId);
    }

    @Override
    public PageResult<FileEntity> listFilesByTag(Long userId, Long tagId, int page, int size) {
        Page<FileTagEntity> ftPage = fileTagMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<FileTagEntity>()
                        .eq(FileTagEntity::getTagId, tagId)
                        .orderByDesc(FileTagEntity::getCreatedAt));

        if (ftPage.getRecords().isEmpty()) {
            return PageResult.of(List.of(), 0L, page, size);
        }

        List<Long> fileIds = ftPage.getRecords().stream()
                .map(FileTagEntity::getFileId)
                .collect(Collectors.toList());

        List<FileEntity> files = fileMapper.selectBatchIds(fileIds);
        return PageResult.of(files, ftPage.getTotal(), page, size);
    }
}
