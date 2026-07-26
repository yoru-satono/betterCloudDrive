package com.betterclouddrive.service.queue;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.service.observability.AsyncTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TeamTaskOutboxWriter {
    public static final String AGGREGATE_TEAM_TASK = "TEAM_TASK";
    public static final String EVENT_TASK_READY = "TEAM_TASK_READY";

    private final TeamEventOutboxRepository repository;
    private final AsyncTraceService asyncTraceService;

    public void enqueue(TeamTaskEntity task) {
        LocalDateTime now = LocalDateTime.now();
        AsyncTraceService.TraceContext trace = asyncTraceService.capture();
        repository.save(TeamEventOutboxEntity.builder()
                .aggregateType(AGGREGATE_TEAM_TASK)
                .aggregateId(task.getId())
                .eventType(EVENT_TASK_READY)
                .actorUserId(task.getActorUserId())
                .targetType(task.getTaskType())
                .targetId(task.getTeamId())
                .messageParams(Map.of("taskType", task.getTaskType()))
                .traceparent(trace.traceparent())
                .tracestate(trace.tracestate())
                .status(TeamEventOutboxEntity.STATUS_PENDING)
                .retryCount(0).availableAt(now).createdAt(now).updatedAt(now)
                .build());
    }
}
