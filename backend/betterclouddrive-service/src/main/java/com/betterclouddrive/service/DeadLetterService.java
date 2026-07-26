package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.service.dto.DeadLetterView;
import org.springframework.amqp.core.Message;

import java.util.List;
import java.util.UUID;

public interface DeadLetterService {
    void capture(Message message);
    PageResult<DeadLetterView> list(String queue, String category, String status, int page, int size);
    DeadLetterView get(UUID id);
    List<DeadLetterView> replay(Long actorUserId, List<UUID> ids);
    DeadLetterView discard(Long actorUserId, UUID id, String reason);
}
