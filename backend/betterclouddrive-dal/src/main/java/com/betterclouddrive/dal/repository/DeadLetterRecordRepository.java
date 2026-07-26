package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.DeadLetterRecordEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface DeadLetterRecordRepository extends JpaRepository<DeadLetterRecordEntity, UUID>,
        JpaSpecificationExecutor<DeadLetterRecordEntity> {
    Optional<DeadLetterRecordEntity> findByOriginalMessageId(String originalMessageId);

    long countByOriginalQueueAndFailureCategoryAndStatus(String queue, String category, String status);

    @Query("select d.originalQueue, d.failureCategory, count(d) from DeadLetterRecordEntity d " +
            "where d.status = 'OPEN' group by d.originalQueue, d.failureCategory")
    List<Object[]> countOpenGrouped();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeadLetterRecordEntity d where d.id = :id")
    Optional<DeadLetterRecordEntity> findByIdForUpdate(@Param("id") UUID id);
}
