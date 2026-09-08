package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TagEntity;
import com.betterclouddrive.service.dto.TagSummaryDto;

import java.util.List;

public interface TagService {
    TagEntity createTag(Long userId, String tagName, String color);
    List<TagSummaryDto> listTags(Long userId, String keyword);
    TagEntity updateTag(Long userId, Long tagId, String tagName, String color);
    void deleteTag(Long userId, Long tagId);
    void tagFiles(Long userId, Long tagId, List<Long> fileIds);
    void untagFile(Long userId, Long tagId, Long fileId);
    PageResult<FileEntity> listFilesByTag(Long userId, Long tagId, String keyword, int page, int size);
    List<TagEntity> listFileTags(Long userId, Long fileId);
    List<TagEntity> replaceFileTags(Long userId, Long fileId, List<Long> tagIds);

    TagEntity createTeamTag(Long actorUserId, Long teamId, String tagName, String color);
    List<TagEntity> listTeamTags(Long userId, Long teamId, String keyword);
    TagEntity updateTeamTag(Long actorUserId, Long teamId, Long tagId, String tagName, String color);
    void deleteTeamTag(Long actorUserId, Long teamId, Long tagId);
    void tagTeamFiles(Long actorUserId, Long teamId, Long tagId, List<Long> fileIds);
    void untagTeamFile(Long actorUserId, Long teamId, Long tagId, Long fileId);
    PageResult<FileEntity> listTeamFilesByTag(Long userId, Long teamId, Long tagId, String keyword, int page, int size);
    List<TagEntity> listTeamFileTags(Long userId, Long teamId, Long fileId);
    List<TagEntity> replaceTeamFileTags(Long actorUserId, Long teamId, Long fileId, List<Long> tagIds);
}
