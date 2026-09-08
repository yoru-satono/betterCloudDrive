package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TagRepository extends JpaRepository<TagEntity, Long> {

    List<TagEntity> findByUserIdOrderByTagNameAsc(Long userId);

    List<TagEntity> findByTeamIdOrderByTagNameAsc(Long teamId);

    List<TagEntity> findByUserIdAndTagNameContainingIgnoreCaseOrderByTagNameAsc(Long userId, String keyword);

    List<TagEntity> findByTeamIdAndTagNameContainingIgnoreCaseOrderByTagNameAsc(Long teamId, String keyword);

    boolean existsByUserIdAndTagName(Long userId, String tagName);

    boolean existsByUserIdAndTagNameAndIdNot(Long userId, String tagName, Long id);

    boolean existsByTeamIdAndTagName(Long teamId, String tagName);

    boolean existsByTeamIdAndTagNameAndIdNot(Long teamId, String tagName, Long id);
}
