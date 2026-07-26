package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.dal.entity.DeadLetterRecordEntity;
import com.betterclouddrive.dal.repository.DeadLetterRecordRepository;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.service.DeadLetterService;
import com.betterclouddrive.service.OperationLogService;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.dto.DeadLetterView;
import com.betterclouddrive.service.queue.RabbitQueueService;
import com.betterclouddrive.service.metrics.DeadLetterMetrics;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DeadLetterServiceImpl implements DeadLetterService {
    public static final int MAX_REPLAY_BATCH = 20;
    private static final Set<String> QUEUES = Set.of("notifications", "team-tasks", "unknown");
    private static final Set<String> CATEGORIES =
            Set.of("invalid-payload", "missing-record", "delivery-exhausted", "unknown");
    private static final Set<String> TASK_TYPES =
            Set.of("USER", "TEAM_MEMBERS", "AUDIT_EXPORT", "THUMBNAIL", "FOLDER_ZIP", "ONLYOFFICE_SAVE", "unknown");

    private final DeadLetterRecordRepository repository;
    private final RabbitQueueService rabbitQueueService;
    private final QueueProperties queueProperties;
    private final OperationLogService operationLogService;
    private final DeadLetterMetrics metrics;
    private final TeamEventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional
    public void capture(Message message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        Map<String, String> body;
        try {
            body = rabbitQueueService.read(message);
        } catch (RuntimeException ignored) {
            body = Map.of();
        }
        String originalQueue = queue(message);
        String messageId = bounded(message.getMessageProperties().getMessageId(), 128);
        if (!StringUtils.hasText(messageId)) {
            messageId = UUID.nameUUIDFromBytes(message.getBody()).toString();
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Optional<DeadLetterRecordEntity> existing = repository.findByOriginalMessageId(messageId);
        if (existing.isPresent()) {
            DeadLetterRecordEntity record = existing.get();
            record.setDeliveryCount(Math.max(record.getDeliveryCount(), deliveryCount(message, body)));
            record.setLastFailedAt(now);
            if (DeadLetterRecordEntity.STATUS_REPLAYING.equals(record.getStatus())) {
                record.setStatus(DeadLetterRecordEntity.STATUS_OPEN);
            }
            repository.save(record);
            return;
        }
        String traceparent = header(message, "traceparent", 55);
        repository.save(DeadLetterRecordEntity.builder()
                .id(UUID.randomUUID())
                .originalMessageId(messageId)
                .originalQueue(originalQueue)
                .routingKey(routingKey(originalQueue))
                .taskType(taskType(body))
                .failureCategory(category(originalQueue, body))
                .deliveryCount(deliveryCount(message, body))
                .firstFailedAt(firstFailure(message, now))
                .lastFailedAt(now)
                .requestId(header(message, "request_id", 128))
                .traceId(traceId(traceparent))
                .traceparent(traceparent)
                .tracestate(header(message, "tracestate", 512))
                .testMarker(testMarker(body))
                .payload(payload)
                .status(DeadLetterRecordEntity.STATUS_OPEN)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<DeadLetterView> list(String queue, String category, String status, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        Specification<DeadLetterRecordEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(queue)) predicates.add(cb.equal(root.get("originalQueue"), allowed(queue, QUEUES)));
            if (StringUtils.hasText(category)) predicates.add(cb.equal(root.get("failureCategory"), allowed(category, CATEGORIES)));
            if (StringUtils.hasText(status)) predicates.add(cb.equal(root.get("status"), normalizedStatus(status)));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<DeadLetterRecordEntity> result = repository.findAll(spec,
                PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "lastFailedAt")));
        return PageResult.of(result.map(DeadLetterView::from).getContent(), result.getTotalElements(), safePage, safeSize);
    }

    @Override
    @Transactional(readOnly = true)
    public DeadLetterView get(UUID id) {
        return DeadLetterView.from(repository.findById(id)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Dead letter not found")));
    }

    @Override
    public List<DeadLetterView> replay(Long actorUserId, List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_REPLAY_BATCH || new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Replay requires 1 to 20 unique dead-letter ids");
        }
        List<DeadLetterView> result = new ArrayList<>();
        for (UUID id : ids) {
            try {
                result.add(replayOne(actorUserId, id));
                metrics.record("replay", "success");
            } catch (RuntimeException error) {
                metrics.record("replay", "failure");
                throw error;
            }
        }
        return List.copyOf(result);
    }

    private DeadLetterView replayOne(Long actorUserId, UUID id) {
        UUID token = UUID.randomUUID();
        DeadLetterRecordEntity prepared = transaction().execute(status -> {
            DeadLetterRecordEntity record = openForUpdate(id);
            record.setStatus(DeadLetterRecordEntity.STATUS_REPLAYING);
            record.setReplayToken(token);
            prepareDurableFact(record);
            return repository.save(record);
        });
        if (prepared == null) throw new IllegalStateException("Dead-letter replay preparation failed");
        try {
            rabbitQueueService.republish(prepared.getRoutingKey(), UUID.randomUUID().toString(), prepared.getPayload(),
                    prepared.getTraceparent(), prepared.getTracestate(), token.toString());
        } catch (RuntimeException error) {
            transaction().executeWithoutResult(status -> compensateReplay(id, token));
            throw error;
        }
        DeadLetterView view = transaction().execute(status -> {
            DeadLetterRecordEntity record = repository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Dead letter not found"));
            if (!DeadLetterRecordEntity.STATUS_REPLAYING.equals(record.getStatus())
                    || !token.equals(record.getReplayToken())) {
                throw new BusinessException(ApiCode.CONFLICT, "Dead-letter replay state changed unexpectedly");
            }
            record.setStatus(DeadLetterRecordEntity.STATUS_REPLAYED);
            record.setResolvedBy(actorUserId);
            record.setResolvedAt(LocalDateTime.now(ZoneOffset.UTC));
            return DeadLetterView.from(repository.save(record));
        });
        if (view == null) throw new IllegalStateException("Dead-letter replay completion failed");
        operationLogService.logSuccess(actorUserId, "DEAD_LETTER_REPLAY", "DEAD_LETTER", null,
                "{\"deadLetterId\":\"" + id + "\",\"queue\":\"" + prepared.getOriginalQueue() + "\"}");
        return view;
    }

    @Override
    @Transactional
    public DeadLetterView discard(Long actorUserId, UUID id, String reason) {
        try {
            return discardInternal(actorUserId, id, reason);
        } catch (RuntimeException error) {
            metrics.record("discard", "failure");
            throw error;
        }
    }

    private DeadLetterView discardInternal(Long actorUserId, UUID id, String reason) {
        if (!StringUtils.hasText(reason) || reason.trim().length() < 3 || reason.length() > 500) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Discard reason must contain 3 to 500 characters");
        }
        DeadLetterRecordEntity record = openForUpdate(id);
        record.setStatus(DeadLetterRecordEntity.STATUS_DISCARDED);
        record.setResolutionReason(reason.trim());
        record.setResolvedBy(actorUserId);
        record.setResolvedAt(LocalDateTime.now(ZoneOffset.UTC));
        DeadLetterView view = DeadLetterView.from(repository.save(record));
        metrics.record("discard", "success");
        operationLogService.logSuccess(actorUserId, "DEAD_LETTER_DISCARD", "DEAD_LETTER", null,
                "{\"deadLetterId\":\"" + id + "\",\"queue\":\"" + record.getOriginalQueue() + "\"}");
        return view;
    }

    private DeadLetterRecordEntity openForUpdate(UUID id) {
        DeadLetterRecordEntity record = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Dead letter not found"));
        if (!DeadLetterRecordEntity.STATUS_OPEN.equals(record.getStatus())) {
            throw new BusinessException(ApiCode.CONFLICT, "Dead letter has already been resolved");
        }
        return record;
    }

    private void prepareDurableFact(DeadLetterRecordEntity record) {
        if (!"notifications".equals(record.getOriginalQueue())) return;
        try {
            Map<?, ?> body = objectMapper.readValue(record.getPayload(), Map.class);
            Long outboxId = Long.valueOf(String.valueOf(body.get("outboxId")));
            TeamEventOutboxEntity outbox = outboxRepository.findById(outboxId)
                    .orElseThrow(() -> new BusinessException(ApiCode.CONFLICT,
                            "Notification outbox fact no longer exists; discard this dead letter"));
            if (!TeamEventOutboxEntity.STATUS_FAILED.equals(outbox.getStatus())) {
                throw new BusinessException(ApiCode.CONFLICT,
                        "Notification outbox is not in a replayable failed state");
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            outbox.setStatus(TeamEventOutboxEntity.STATUS_PROCESSING);
            outbox.setProcessedAt(null);
            outbox.setErrorMessage(null);
            outbox.setUpdatedAt(now);
            outboxRepository.save(outbox);
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(ApiCode.CONFLICT,
                    "Dead-letter payload cannot be mapped to its durable notification fact");
        }
    }

    private void compensateReplay(UUID id, UUID token) {
        DeadLetterRecordEntity record = repository.findByIdForUpdate(id).orElse(null);
        if (record == null || !DeadLetterRecordEntity.STATUS_REPLAYING.equals(record.getStatus())
                || !token.equals(record.getReplayToken())) return;
        if ("notifications".equals(record.getOriginalQueue())) {
            notificationOutbox(record).ifPresent(outbox -> {
                if (TeamEventOutboxEntity.STATUS_PROCESSING.equals(outbox.getStatus())) {
                    outbox.setStatus(TeamEventOutboxEntity.STATUS_FAILED);
                    outbox.setProcessedAt(LocalDateTime.now(ZoneOffset.UTC));
                    outboxRepository.save(outbox);
                }
            });
        }
        record.setStatus(DeadLetterRecordEntity.STATUS_OPEN);
        record.setReplayToken(null);
        repository.save(record);
    }

    private Optional<TeamEventOutboxEntity> notificationOutbox(DeadLetterRecordEntity record) {
        try {
            Map<?, ?> body = objectMapper.readValue(record.getPayload(), Map.class);
            return outboxRepository.findById(Long.valueOf(String.valueOf(body.get("outboxId"))));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private String queue(Message message) {
        String value = header(message, "x-first-death-queue", 128);
        if (queueProperties.getRabbitmq().getNotificationsQueue().equals(value)) return "notifications";
        if (queueProperties.getRabbitmq().getTeamTasksQueue().equals(value)) return "team-tasks";
        return "unknown";
    }

    private String routingKey(String queue) {
        return switch (queue) {
            case "notifications" -> queueProperties.getRabbitmq().getNotificationsRoutingKey();
            case "team-tasks" -> queueProperties.getRabbitmq().getTeamTasksRoutingKey();
            default -> "unknown";
        };
    }

    private String taskType(Map<String, String> body) {
        String value = body.get("targetType");
        return TASK_TYPES.contains(value) ? value : "unknown";
    }

    private String testMarker(Map<String, String> body) {
        String marker = bounded(body.get("testMarker"), 128);
        return marker != null && marker.startsWith("bcd-e2e:") ? marker : null;
    }

    private String category(String queue, Map<String, String> body) {
        if (body.isEmpty() || "unknown".equals(taskType(body))) return "invalid-payload";
        if ("notifications".equals(queue) && !StringUtils.hasText(body.get("outboxId"))) return "missing-record";
        return "delivery-exhausted";
    }

    private int deliveryCount(Message message, Map<String, String> body) {
        int count = parseDeliveryCount(body.get("deliveryCount"));
        Object deaths = message.getMessageProperties().getHeaders().get("x-death");
        if (deaths instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> death) {
            Object deathCount = death.get("count");
            if (deathCount instanceof Number number) count = Math.max(count, number.intValue());
        }
        return Math.max(1, count);
    }

    private int parseDeliveryCount(String value) {
        if (!StringUtils.hasText(value)) return 0;
        try {
            return Math.clamp(Integer.parseInt(value), 0, 100_000);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private LocalDateTime firstFailure(Message message, LocalDateTime fallback) {
        Object value = message.getMessageProperties().getHeaders().get("x-first-death-time");
        if (value instanceof Date date) return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
        Object deaths = message.getMessageProperties().getHeaders().get("x-death");
        if (deaths instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(death -> death.get("time"))
                    .filter(Date.class::isInstance)
                    .map(Date.class::cast)
                    .map(date -> LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC))
                    .min(LocalDateTime::compareTo)
                    .orElse(fallback);
        }
        return fallback;
    }

    private String header(Message message, String name, int max) {
        Object value = message.getMessageProperties().getHeaders().get(name);
        return bounded(value == null ? null : String.valueOf(value), max);
    }

    private String traceId(String traceparent) {
        if (!StringUtils.hasText(traceparent)) return null;
        String[] parts = traceparent.split("-");
        return parts.length == 4 && parts[1].matches("[0-9a-f]{32}") ? parts[1] : null;
    }

    private String allowed(String value, Set<String> allowed) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BusinessException(ApiCode.BAD_REQUEST, "Unsupported dead-letter filter");
        return normalized;
    }

    private String normalizedStatus(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("OPEN", "REPLAYING", "REPLAYED", "DISCARDED").contains(normalized)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Unsupported dead-letter status");
        }
        return normalized;
    }

    private String bounded(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
