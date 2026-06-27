package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TagRepository extends JpaRepository<TagEntity, Long> {

    List<TagEntity> findByUserIdOrderByTagNameAsc(Long userId);

    List<TagEntity> findByUserIdAndTagNameContainingIgnoreCaseOrderByTagNameAsc(Long userId, String keyword);

    boolean existsByUserIdAndTagName(Long userId, String tagName);
}
