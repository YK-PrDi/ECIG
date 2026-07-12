package com.elebusiness.service.billing;

import com.elebusiness.model.entity.BillingReconciliationAnomalyAction;
import com.elebusiness.repository.BillingReconciliationAnomalyActionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Service
public class BillingReconciliationAnomalyActionService {

    private static final Set<String> ALLOWED_STATUSES = Set.of("OPEN", "ACKNOWLEDGED", "IGNORED", "RESOLVED");

    private final BillingReconciliationAnomalyActionRepository repository;

    public BillingReconciliationAnomalyActionService(BillingReconciliationAnomalyActionRepository repository) {
        this.repository = repository;
    }

    public BillingReconciliationAnomalyAction recordAction(ActionRequest request, Long operatorUserId) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处理请求不能为空");
        }
        String type = normalizeRequired(request.type(), "type");
        Long sourceId = request.sourceId();
        if (sourceId == null || sourceId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceId 必须大于 0");
        }
        String status = normalizeRequired(request.status(), "status");
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的异常处理状态: " + status);
        }
        if (operatorUserId == null || operatorUserId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operatorUserId 必须大于 0");
        }

        String anomalyKey = anomalyKey(type, sourceId);
        BillingReconciliationAnomalyAction action = repository.findByAnomalyKey(anomalyKey)
                .orElseGet(BillingReconciliationAnomalyAction::new);
        action.setAnomalyKey(anomalyKey);
        action.setType(type);
        action.setSourceId(sourceId);
        action.setUserId(request.userId());
        action.setReferenceNo(trimToNull(request.referenceNo()));
        action.setStatus(status);
        action.setNote(trimToNull(request.note()));
        action.setOperatorUserId(operatorUserId);
        return repository.save(action);
    }

    public ActionPage listActions(String type, Long userId, String status, Long cursor, int limit) {
        String safeType = normalizeOptional(type);
        String safeStatus = normalizeOptional(status);
        if (safeStatus != null && !ALLOWED_STATUSES.contains(safeStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的异常处理状态: " + safeStatus);
        }
        int safeLimit = Math.max(1, Math.min(200, limit));
        PageRequest pageRequest = PageRequest.of(0, safeLimit);
        Slice<BillingReconciliationAnomalyAction> slice =
                repository.searchActions(safeType, userId, safeStatus, cursor, pageRequest);
        List<BillingReconciliationAnomalyAction> items = slice.getContent();
        return new ActionPage(items, safeLimit, slice.hasNext(), nextCursor(items));
    }

    private String anomalyKey(String type, Long sourceId) {
        return type + ":" + sourceId;
    }

    private Long nextCursor(List<BillingReconciliationAnomalyAction> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return items.get(items.size() - 1).getId();
    }

    private String normalizeRequired(String value, String name) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 不能为空");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record ActionRequest(String type, Long sourceId, Long userId, String referenceNo,
                                String status, String note) {}

    public record ActionPage(List<BillingReconciliationAnomalyAction> items, int limit,
                             boolean hasMore, Long nextCursor) {}
}
