package com.elebusiness.service.billing;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.BillingExportAuditLog;
import com.elebusiness.repository.BillingExportAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingExportAuditServiceTest {
    @Test
    void springCanCreateServiceUsingRepositoryConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            BillingExportAuditLogRepository repository = mock(BillingExportAuditLogRepository.class);
            context.registerBean(AppProperties.class, AppProperties::new);
            context.registerBean(BillingExportAuditLogRepository.class, () -> repository);
            context.register(BillingExportAuditService.class);

            context.refresh();

            assertNotNull(context.getBean(BillingExportAuditService.class));
        }
    }
    @Test
    void recordSuccessCountsCsvRowsMarksTruncationAndSerializesFilters() {
        AppProperties appProperties = new AppProperties();
        appProperties.getBilling().setExportMaxRows(2);
        BillingExportAuditLogRepository repository = mock(BillingExportAuditLogRepository.class);
        when(repository.save(any(BillingExportAuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        BillingExportAuditService service = new BillingExportAuditService(appProperties, repository);

        BillingExportAuditLog log = service.recordSuccess(
                1L,
                2002L,
                "LEDGER",
                Map.of("scope", "ADMIN", "type", "GENERATION_CHARGE"),
                "\uFEFFid,userId\n2,2002\n1,2002\n"
        );

        assertEquals(1L, log.getOperatorUserId());
        assertEquals(2002L, log.getScopeUserId());
        assertEquals("LEDGER", log.getExportType());
        assertEquals("SUCCESS", log.getStatus());
        assertEquals(2, log.getRowCount());
        assertEquals(true, log.isTruncated());
        assertTrue(log.getFiltersJson().contains("\"type\":\"GENERATION_CHARGE\""));
        verify(repository).save(log);
    }

    @Test
    void recordFailureKeepsFailureReasonWithoutCountingRows() {
        BillingExportAuditLogRepository repository = mock(BillingExportAuditLogRepository.class);
        when(repository.save(any(BillingExportAuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        BillingExportAuditService service = new BillingExportAuditService(new AppProperties(), repository);

        BillingExportAuditLog log = service.recordFailure(
                1L,
                2002L,
                "usage",
                Map.of("scope", "ADMIN", "status", "SUCCEEDED"),
                new IllegalStateException("database unavailable")
        );

        assertEquals(1L, log.getOperatorUserId());
        assertEquals(2002L, log.getScopeUserId());
        assertEquals("USAGE", log.getExportType());
        assertEquals("FAILED", log.getStatus());
        assertEquals(0, log.getRowCount());
        assertEquals(false, log.isTruncated());
        assertTrue(log.getErrorMessage().contains("database unavailable"));
        assertTrue(log.getFiltersJson().contains("\"status\":\"SUCCEEDED\""));
        verify(repository).save(log);
    }

    @Test
    void listLogsUsesCursorPaginationAndCapsLimit() {
        AppProperties appProperties = new AppProperties();
        BillingExportAuditLogRepository repository = mock(BillingExportAuditLogRepository.class);
        BillingExportAuditService service = new BillingExportAuditService(appProperties, repository);
        BillingExportAuditLog log = new BillingExportAuditLog();
        log.setId(66L);
        log.setExportId("export-66");
        log.setOperatorUserId(1L);
        log.setScopeUserId(2002L);
        log.setExportType("USAGE");
        log.setStatus("SUCCESS");
        when(repository.searchLogs(1L, "USAGE", "SUCCESS", 120L, PageRequest.of(0, 200)))
                .thenReturn(new SliceImpl<>(List.of(log), PageRequest.of(0, 200), true));

        BillingExportAuditService.ExportAuditPage page =
                service.listLogs(1L, "usage", "success", 120L, 500);

        assertEquals(1, page.items().size());
        assertEquals(200, page.limit());
        assertEquals(true, page.hasMore());
        assertEquals(66L, page.nextCursor());
        verify(repository).searchLogs(1L, "USAGE", "SUCCESS", 120L, PageRequest.of(0, 200));
    }

    @Test
    void exportLogsForAdminUsesCursorPagesAndEscapesFiltersJson() {
        AppProperties appProperties = new AppProperties();
        BillingExportAuditLogRepository repository = mock(BillingExportAuditLogRepository.class);
        BillingExportAuditService service = new BillingExportAuditService(appProperties, repository);
        BillingExportAuditLog first = exportLog(
                90L, "export-90", 1L, 2002L, "USAGE",
                "{\"scope\":\"ADMIN\",\"type\":\"USAGE\"}", 2L, true, "SUCCESS", null);
        BillingExportAuditLog second = exportLog(
                70L, "export-70", 2L, null, "LEDGER",
                "{\"scope\":\"USER\"}", 1L, false, "FAILED", "write failed");
        when(repository.searchLogs(1L, "USAGE", "SUCCESS", null, PageRequest.of(0, 500)))
                .thenReturn(new SliceImpl<>(List.of(first), PageRequest.of(0, 500), true));
        when(repository.searchLogs(1L, "USAGE", "SUCCESS", 90L, PageRequest.of(0, 500)))
                .thenReturn(new SliceImpl<>(List.of(second), PageRequest.of(0, 500), false));

        String csv = service.exportLogsForAdmin(1L, "usage", "success");

        assertTrue(csv.startsWith("\uFEFFid,exportId,operatorUserId,scopeUserId,exportType,filtersJson,rowCount,truncated,status,errorMessage,startedAt,finishedAt,durationMillis"));
        assertTrue(csv.contains("90,export-90,1,2002,USAGE,\"{\"\"scope\"\":\"\"ADMIN\"\",\"\"type\"\":\"\"USAGE\"\"}\",2,true,SUCCESS,,2026-07-10T08:00,2026-07-10T08:00:02,2000"));
        assertTrue(csv.contains("70,export-70,2,,LEDGER,\"{\"\"scope\"\":\"\"USER\"\"}\",1,false,FAILED,write failed,2026-07-10T08:00,2026-07-10T08:00:02,2000"));
        verify(repository).searchLogs(1L, "USAGE", "SUCCESS", null, PageRequest.of(0, 500));
        verify(repository).searchLogs(1L, "USAGE", "SUCCESS", 90L, PageRequest.of(0, 500));
    }

    private BillingExportAuditLog exportLog(long id,
                                            String exportId,
                                            Long operatorUserId,
                                            Long scopeUserId,
                                            String exportType,
                                            String filtersJson,
                                            long rowCount,
                                            boolean truncated,
                                            String status,
                                            String errorMessage) {
        BillingExportAuditLog log = new BillingExportAuditLog();
        log.setId(id);
        log.setExportId(exportId);
        log.setOperatorUserId(operatorUserId);
        log.setScopeUserId(scopeUserId);
        log.setExportType(exportType);
        log.setFiltersJson(filtersJson);
        log.setRowCount(rowCount);
        log.setTruncated(truncated);
        log.setStatus(status);
        log.setErrorMessage(errorMessage);
        log.setStartedAt(LocalDateTime.of(2026, 7, 10, 8, 0));
        log.setFinishedAt(LocalDateTime.of(2026, 7, 10, 8, 0, 2));
        log.setDurationMillis(2000);
        return log;
    }
}
