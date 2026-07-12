package com.elebusiness.service.billing;

import com.elebusiness.model.entity.BillingReconciliationRun;
import com.elebusiness.repository.BillingReconciliationRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class BillingReconciliationRunService {

    public static final long GLOBAL_SCOPE_USER_ID = BillingDailySummaryService.GLOBAL_SCOPE_USER_ID;

    private final BillingReconciliationService reconciliationService;
    private final BillingReconciliationRunRepository runRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public BillingReconciliationRunService(BillingReconciliationService reconciliationService,
                                           BillingReconciliationRunRepository runRepository) {
        this(reconciliationService, runRepository, new ObjectMapper());
    }

    BillingReconciliationRunService(BillingReconciliationService reconciliationService,
                                    BillingReconciliationRunRepository runRepository,
                                    ObjectMapper objectMapper) {
        this.reconciliationService = reconciliationService;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    public RunResult runAndRecord(Long userId, String triggerType, Long triggeredByUserId) {
        LocalDateTime startedAt = LocalDateTime.now();
        BillingReconciliationRun run = new BillingReconciliationRun();
        run.setRunId(UUID.randomUUID().toString().replace("-", ""));
        run.setScopeUserId(scopeUserId(userId));
        run.setTriggeredByUserId(triggeredByUserId);
        run.setTriggerType(normalize(triggerType, "MANUAL"));
        run.setStatus("RUNNING");
        run.setStartedAt(startedAt);

        try {
            BillingReconciliationService.Report report = reconciliationService.reconcile(aggregateUserId(run.getScopeUserId()));
            run.setStatus("SUCCESS");
            run.setHealthy(report.healthy());
            run.setCheckCount(report.checks() == null ? 0 : report.checks().size());
            run.setAnomalyCount(anomalyCount(report));
            run.setReportJson(toJson(report));
            finish(run, startedAt, null);
            BillingReconciliationRun saved = runRepository.save(run);
            return new RunResult(saved, report);
        } catch (RuntimeException e) {
            run.setStatus("FAILED");
            run.setHealthy(false);
            finish(run, startedAt, e);
            runRepository.save(run);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public RunPage listRuns(Long userId, String status, String triggerType,
                            LocalDateTime fromTime, LocalDateTime toTime,
                            Long cursor, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        Long scopeFilter = scopeFilter(userId);
        String safeStatus = normalizeOptional(status);
        String safeTriggerType = normalizeOptional(triggerType);
        Long safeCursor = cursor == null ? Long.MAX_VALUE : cursor;
        Slice<BillingReconciliationRun> slice = runRepository.searchBeforeId(
                scopeFilter, safeStatus, safeTriggerType, fromTime, toTime, safeCursor, PageRequest.of(0, safeLimit));
        List<BillingReconciliationRun> items = slice.getContent();
        return new RunPage(items, safeLimit, slice.hasNext(), nextCursor(items));
    }

    @Transactional(readOnly = true)
    public BillingReconciliationRun getByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        return runRepository.findByRunId(runId.trim()).orElseThrow(() ->
                new IllegalArgumentException("reconciliation run not found: " + runId));
    }

    private void finish(BillingReconciliationRun run, LocalDateTime startedAt, RuntimeException error) {
        LocalDateTime finishedAt = LocalDateTime.now();
        run.setFinishedAt(finishedAt);
        run.setDurationMillis(Math.max(0, Duration.between(startedAt, finishedAt).toMillis()));
        if (error != null) {
            run.setErrorMessage(truncate(error.getMessage(), 2000));
        }
    }

    private long anomalyCount(BillingReconciliationService.Report report) {
        if (report == null || report.checks() == null) {
            return 0L;
        }
        return report.checks().stream()
                .filter(check -> !check.ok())
                .mapToLong(BillingReconciliationService.Check::difference)
                .sum();
    }

    private String toJson(BillingReconciliationService.Report report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            return "{\"serializationError\":\"" + truncate(e.getMessage(), 500) + "\"}";
        }
    }

    private Long nextCursor(List<BillingReconciliationRun> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return items.get(items.size() - 1).getId();
    }

    private Long scopeFilter(Long userId) {
        if (userId == null) {
            return null;
        }
        return scopeUserId(userId);
    }

    private Long scopeUserId(Long userId) {
        return userId == null || userId <= 0 ? GLOBAL_SCOPE_USER_ID : userId;
    }

    private Long aggregateUserId(Long scopeUserId) {
        return GLOBAL_SCOPE_USER_ID == scopeUserId ? null : scopeUserId;
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalize(value, null);
    }

    private String normalize(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return truncate(normalized, 32);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record RunResult(BillingReconciliationRun run, BillingReconciliationService.Report report) {}

    public record RunPage(List<BillingReconciliationRun> items, int limit, boolean hasMore, Long nextCursor) {}
}
