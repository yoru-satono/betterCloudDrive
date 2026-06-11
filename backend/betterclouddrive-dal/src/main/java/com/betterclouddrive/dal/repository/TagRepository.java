package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.TagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TagRepository extends JpaRepository<TagEntity, Long> {

    List<TagEntity> findByUserIdOrderByTagNameAsc(Long userId);

    boolean existsByUserIdAndTagName(Long userId, String tagName);
}
