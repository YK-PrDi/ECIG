package com.elebusiness.service.billing;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.BillingExportAuditLog;
import com.elebusiness.repository.BillingExportAuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BillingExportAuditService {

    private static final int EXPORT_CHUNK_SIZE = 500;
    private static final String UTF8_BOM = "\uFEFF";

    private final AppProperties appProperties;
    private final BillingExportAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Autowired
    public BillingExportAuditService(AppProperties appProperties,
                                     BillingExportAuditLogRepository repository) {
        this(appProperties, repository, new ObjectMapper());
    }

    BillingExportAuditService(AppProperties appProperties,
                              BillingExportAuditLogRepository repository,
                              ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public BillingExportAuditLog recordSuccess(Long operatorUserId,
                                               Long scopeUserId,
                                               String exportType,
                                               Map<String, Object> filters,
                                               String csv) {
        LocalDateTime now = LocalDateTime.now();
        long rowCount = countCsvRows(csv);
        BillingExportAuditLog log = new BillingExportAuditLog();
        log.setExportId(UUID.randomUUID().toString().replace("-", ""));
        log.setOperatorUserId(operatorUserId);
        log.setScopeUserId(scopeUserId);
        log.setExportType(normalizeExportType(exportType));
        log.setFiltersJson(filtersJson(filters));
        log.setRowCount(rowCount);
        log.setTruncated(rowCount >= exportMaxRows());
        log.setStatus("SUCCESS");
        log.setStartedAt(now);
        log.setFinishedAt(now);
        log.setDurationMillis(0);
        return repository.save(log);
    }

    public BillingExportAuditLog recordFailure(Long operatorUserId,
                                               Long scopeUserId,
                                               String exportType,
                                               Map<String, Object> filters,
                                               Throwable failure) {
        LocalDateTime now = LocalDateTime.now();
        BillingExportAuditLog log = new BillingExportAuditLog();
        log.setExportId(UUID.randomUUID().toString().replace("-", ""));
        log.setOperatorUserId(operatorUserId);
        log.setScopeUserId(scopeUserId);
        log.setExportType(normalizeExportType(exportType));
        log.setFiltersJson(filtersJson(filters));
        log.setRowCount(0);
        log.setTruncated(false);
        log.setStatus("FAILED");
        log.setErrorMessage(errorMessage(failure));
        log.setStartedAt(now);
        log.setFinishedAt(now);
        log.setDurationMillis(0);
        return repository.save(log);
    }

    public ExportAuditPage listLogs(Long operatorUserId, String exportType, String status, Long cursor, int limit) {
        String safeExportType = normalizeOptional(exportType);
        String safeStatus = normalizeOptional(status);
        int safeLimit = Math.max(1, Math.min(200, limit));
        Slice<BillingExportAuditLog> slice = repository.searchLogs(
                operatorUserId, safeExportType, safeStatus, cursor, PageRequest.of(0, safeLimit));
        List<BillingExportAuditLog> items = slice.getContent();
        return new ExportAuditPage(items, safeLimit, slice.hasNext(), nextCursor(items));
    }

    public String exportLogsForAdmin(Long operatorUserId, String exportType, String status) {
        String safeExportType = normalizeOptional(exportType);
        String safeStatus = normalizeOptional(status);
        StringBuilder csv = new StringBuilder(UTF8_BOM)
                .append("id,exportId,operatorUserId,scopeUserId,exportType,filtersJson,rowCount,truncated,status,errorMessage,startedAt,finishedAt,durationMillis\n");
        Long cursor = null;
        int written = 0;
        int maxRows = exportMaxRows();
        boolean hasMore;
        do {
            Slice<BillingExportAuditLog> slice = repository.searchLogs(
                    operatorUserId, safeExportType, safeStatus, cursor, PageRequest.of(0, EXPORT_CHUNK_SIZE));
            Long nextCursor = null;
            for (BillingExportAuditLog log : slice.getContent()) {
                if (written >= maxRows) {
                    return csv.toString();
                }
                appendRow(csv, Arrays.asList(
                        log.getId(),
                        log.getExportId(),
                        log.getOperatorUserId(),
                        log.getScopeUserId(),
                        log.getExportType(),
                        log.getFiltersJson(),
                        log.getRowCount(),
                        log.isTruncated(),
                        log.getStatus(),
                        log.getErrorMessage(),
                        log.getStartedAt(),
                        log.getFinishedAt(),
                        log.getDurationMillis()
                ));
                nextCursor = log.getId();
                written++;
            }
            hasMore = slice.hasNext();
            cursor = nextCursor;
        } while (hasMore && written < maxRows && cursor != null);
        return csv.toString();
    }

    private String normalizeExportType(String exportType) {
        if (exportType == null || exportType.isBlank()) {
            return "UNKNOWN";
        }
        return exportType.trim().toUpperCase();
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase();
    }

    private String filtersJson(Map<String, Object> filters) {
        try {
            return objectMapper.writeValueAsString(filters == null ? Map.of() : filters);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String errorMessage(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getName();
        }
        return failure.getClass().getName() + ": " + message;
    }

    private long countCsvRows(String csv) {
        if (csv == null || csv.isBlank()) {
            return 0;
        }
        String text = csv.startsWith("\uFEFF") ? csv.substring(1) : csv;
        int lineBreaks = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineBreaks++;
            }
        }
        boolean endsWithLineBreak = text.endsWith("\n");
        int totalLines = lineBreaks + (endsWithLineBreak ? 0 : 1);
        return Math.max(0, totalLines - 1L);
    }

    private int exportMaxRows() {
        AppProperties.Billing billing = appProperties == null ? null : appProperties.getBilling();
        int configured = billing == null ? 10000 : billing.getExportMaxRows();
        return Math.max(1, configured);
    }

    private Long nextCursor(List<BillingExportAuditLog> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return items.get(items.size() - 1).getId();
    }

    private void appendRow(StringBuilder csv, List<Object> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) csv.append(',');
            csv.append(csvCell(values.get(i)));
        }
        csv.append('\n');
    }

    private String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        boolean quote = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r");
        if (!quote) {
            return text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    public record ExportAuditPage(List<BillingExportAuditLog> items, int limit,
                                  boolean hasMore, Long nextCursor) {}
}
