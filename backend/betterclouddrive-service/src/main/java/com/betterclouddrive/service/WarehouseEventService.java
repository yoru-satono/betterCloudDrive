package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.WarehouseEvent;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.repository.WarehouseEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static com.betterclouddrive.common.dto.WarehouseEvent.*;

/** Source-database writes only; analytic infrastructure is never on this path. */
@Service
@RequiredArgsConstructor
public class WarehouseEventService {
    private final WarehouseEventRepository repository;

    @Value("${warehouse.capture-enabled:true}")
    private boolean captureEnabled = true;

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Type type, Long actorId, Long teamId, Long resourceId, String key) {
        if (captureEnabled) repository.append(new WarehouseEvent(UUID.randomUUID(), key, type, Instant.now(),
                actorId, teamId, resourceId, null, Channel.API, Mode.NONE, Outcome.SUCCEEDED, 0));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void uploadCommitted(FileEntity file, Mode mode) {
        if (captureEnabled) repository.append(new WarehouseEvent(UUID.randomUUID(), "upload:" + file.getId(),
                Type.UPLOAD_COMMITTED, Instant.now(), file.getUserId(), file.getTeamId(), file.getId(), null,
                mode == Mode.STREAM ? Channel.WEBDAV : Channel.API, mode, Outcome.SUCCEEDED, file.getFileSize()));
    }

    /** Stream callbacks must commit independently of the request's transaction/lifecycle. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transfer(UUID attemptId, boolean started, Long actorId, Long teamId, Long resourceId,
                         Channel channel, Mode mode, boolean succeeded, long bytes) {
        if (captureEnabled) repository.append(new WarehouseEvent(UUID.randomUUID(),
                "download:" + attemptId + (started ? ":start" : ":end"),
                started ? Type.DOWNLOAD_STARTED : Type.DOWNLOAD_FINISHED, Instant.now(),
                actorId, teamId, resourceId, attemptId, channel, mode,
                started ? Outcome.STARTED : succeeded ? Outcome.SUCCEEDED : Outcome.FAILED, bytes));
    }
}
