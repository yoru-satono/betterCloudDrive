package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TagEntity;

import java.util.List;

public interface TagService {
    TagEntity createTag(Long userId, String tagName, String color);
    List<TagEntity> listTags(Long userId, String keyword);
    TagEntity updateTag(Long userId, Long tagId, String tagName, String color);
    void deleteTag(Long userId, Long tagId);
    void tagFiles(Long userId, Long tagId, List<Long> fileIds);
    void untagFile(Long userId, Long tagId, Long fileId);
    PageResult<FileEntity> listFilesByTag(Long userId, Long tagId, String keyword, int page, int size);
}
