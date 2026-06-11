package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.ShareLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ShareLinkRepository extends JpaRepository<ShareLinkEntity, Long>, JpaSpecificationExecutor<ShareLinkEntity> {

    Optional<ShareLinkEntity> findByShareCode(String shareCode);

    Optional<ShareLinkEntity> findByShareCodeAndIsCanceledFalse(String shareCode);

    @Modifying
    @Query("UPDATE ShareLinkEntity s SET s.visitCount = s.visitCount + :count WHERE s.shareCode = :shareCode")
    int incrementVisitCount(@Param("shareCode") String shareCode, @Param("count") int count);

    List<ShareLinkEntity> findByIsCanceledFalseAndExpireAtLessThanEqual(LocalDateTime now);
}
