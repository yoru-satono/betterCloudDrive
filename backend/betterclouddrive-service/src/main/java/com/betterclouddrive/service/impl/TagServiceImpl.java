package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileTagEntity;
import com.betterclouddrive.dal.entity.TagEntity;
import com.betterclouddrive.dal.repository.FileTagRepository;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.TagRepository;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.TeamService;
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
    private final FileRepository fileRepository;
    private final TeamService teamService;
    private final TeamAuditService teamAuditService;
    private final TeamPermissionService teamPermissionService;

    @Override
    @Transactional
    public TagEntity createTag(Long userId, String tagName, String color) {
        if (tagRepository.existsByUserIdAndTagName(userId, tagName)) {
            throw new BusinessException(ApiCode.CONFLICT, "Tag '" + tagName + "' already exists");
        }

        TagEntity tag = TagEntity.builder()
                .userId(userId)
                .teamId(null)
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
        if (tag == null || tag.getTeamId() != null || !tag.getUserId().equals(userId)) {
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
        if (tag == null || tag.getTeamId() != null || !tag.getUserId().equals(userId)) {
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
        if (tag == null || tag.getTeamId() != null || !tag.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Tag not found");
        }

        for (Long fileId : fileIds) {
            requirePersonalFile(userId, fileId);
            if (!fileTagRepository.existsByFileIdAndTagId(fileId, tagId)) {
                FileTagEntity ft = FileTagEntity.builder()
                        .userId(userId)
                        .fileId(fileId)
                        .teamId(null)
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
        TagEntity tag = tagRepository.findById(tagId).orElse(null);
        if (tag == null || tag.getTeamId() != null || !tag.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Tag not found");
        }
        requirePersonalFile(userId, fileId);
        fileTagRepository.findByFileIdAndTagId(fileId, tagId)
                .ifPresent(ft -> fileTagRepository.deleteById(ft.getId()));
        log.info("User {} removed tag {} from file {}", userId, tagId, fileId);
    }

    @Override
    public PageResult<FileEntity> listFilesByTag(Long userId, Long tagId, String keyword, int page, int size) {
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        PageRequest pageRequest = PageRequest.of(page - 1, size);
        Page<FileEntity> files = normalizedKeyword == null
                ? fileTagRepository.findTaggedFiles(userId, tagId, pageRequest)
                : fileTagRepository.findTaggedFilesByKeyword(userId, tagId, normalizedKeyword, pageRequest);
        return PageResult.of(files.getContent(), files.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public TagEntity createTeamTag(Long actorUserId, Long teamId, String tagName, String color) {
        teamService.requireManageMembers(actorUserId, teamId);
        if (tagRepository.existsByTeamIdAndTagName(teamId, tagName)) {
            throw new BusinessException(ApiCode.CONFLICT, "Team tag '" + tagName + "' already exists");
        }
        TagEntity tag = TagEntity.builder()
                .userId(actorUserId)
                .teamId(teamId)
                .tagName(tagName)
                .color(color != null ? color : "#1890ff")
                .createdAt(LocalDateTime.now())
                .build();
        TagEntity saved = tagRepository.save(tag);
        teamAuditService.record(teamId, actorUserId, "TAG_CREATE", "TAG", saved.getId(), saved.getTagName());
        return saved;
    }

    @Override
    public List<TagEntity> listTeamTags(Long userId, Long teamId, String keyword) {
        teamService.requireRead(userId, teamId);
        if (StringUtils.hasText(keyword)) {
            return tagRepository.findByTeamIdAndTagNameContainingIgnoreCaseOrderByTagNameAsc(teamId, keyword.trim());
        }
        return tagRepository.findByTeamIdOrderByTagNameAsc(teamId);
    }

    @Override
    @Transactional
    public TagEntity updateTeamTag(Long actorUserId, Long teamId, Long tagId, String tagName, String color) {
        teamService.requireManageMembers(actorUserId, teamId);
        TagEntity tag = requireTeamTag(teamId, tagId);
        if (tagName != null) {
            tag.setTagName(tagName);
        }
        if (color != null) {
            tag.setColor(color);
        }
        TagEntity saved = tagRepository.save(tag);
        teamAuditService.record(teamId, actorUserId, "TAG_UPDATE", "TAG", tagId, saved.getTagName());
        return saved;
    }

    @Override
    @Transactional
    public void deleteTeamTag(Long actorUserId, Long teamId, Long tagId) {
        teamService.requireManageMembers(actorUserId, teamId);
        TagEntity tag = requireTeamTag(teamId, tagId);
        fileTagRepository.deleteByTagId(tagId);
        tagRepository.deleteById(tagId);
        teamAuditService.record(teamId, actorUserId, "TAG_DELETE", "TAG", tagId, tag.getTagName());
    }

    @Override
    @Transactional
    public void tagTeamFiles(Long actorUserId, Long teamId, Long tagId, List<Long> fileIds) {
        teamService.requireWrite(actorUserId, teamId);
        requireTeamTag(teamId, tagId);
        for (Long fileId : fileIds) {
            FileEntity file = requireTeamFile(teamId, fileId);
            if (!fileTagRepository.existsByFileIdAndTagId(fileId, tagId)) {
                fileTagRepository.save(FileTagEntity.builder()
                        .userId(actorUserId)
                        .fileId(fileId)
                        .teamId(teamId)
                        .tagId(tagId)
                        .createdAt(LocalDateTime.now())
                        .build());
                teamAuditService.record(teamId, actorUserId, "TAG_ATTACH", "FILE", fileId, file.getFileName());
            }
        }
    }

    @Override
    @Transactional
    public void untagTeamFile(Long actorUserId, Long teamId, Long tagId, Long fileId) {
        teamService.requireWrite(actorUserId, teamId);
        requireTeamTag(teamId, tagId);
        FileEntity file = requireTeamFile(teamId, fileId);
        fileTagRepository.findByFileIdAndTagId(fileId, tagId)
                .ifPresent(ft -> fileTagRepository.deleteById(ft.getId()));
        teamAuditService.record(teamId, actorUserId, "TAG_DETACH", "FILE", fileId, file.getFileName());
    }

    @Override
    public PageResult<FileEntity> listTeamFilesByTag(Long userId, Long teamId, Long tagId, String keyword, int page, int size) {
        teamService.requireRead(userId, teamId);
        requireTeamTag(teamId, tagId);
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        List<FileEntity> readable = (normalizedKeyword == null
                ? fileTagRepository.findTeamTaggedFiles(teamId, tagId)
                : fileTagRepository.findTeamTaggedFilesByKeyword(teamId, tagId, normalizedKeyword))
                .stream()
                .filter(file -> teamPermissionService.effectivePermissions(userId, teamId, file.getId()).canRead())
                .toList();
        return PageResults.fromFiltered(readable, page, size);
    }

    private TagEntity requireTeamTag(Long teamId, Long tagId) {
        TagEntity tag = tagRepository.findById(tagId).orElse(null);
        if (tag == null || !teamId.equals(tag.getTeamId())) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Team tag not found");
        }
        return tag;
    }

    private FileEntity requireTeamFile(Long teamId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !teamId.equals(file.getTeamId()) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    private void requirePersonalFile(Long userId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || file.getTeamId() != null || !userId.equals(file.getUserId()) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
    }
}
