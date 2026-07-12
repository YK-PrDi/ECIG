package com.elebusiness.service.billing;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.GenerationUsageLog;
import com.elebusiness.model.entity.PaymentOrder;
import com.elebusiness.model.entity.WalletLedger;
import com.elebusiness.repository.GenerationUsageLogRepository;
import com.elebusiness.repository.PaymentOrderRepository;
import com.elebusiness.repository.WalletLedgerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

@Service
public class BillingCsvExportService {

    private static final int CHUNK_SIZE = 500;
    private static final int ANOMALY_CHUNK_SIZE = 200;
    private static final String UTF8_BOM = "\uFEFF";

    private final AppProperties appProperties;
    private final WalletLedgerRepository ledgerRepository;
    private final GenerationUsageLogRepository usageRepository;
    private final PaymentOrderRepository orderRepository;
    private final BillingReconciliationService reconciliationService;

    @Autowired
    public BillingCsvExportService(AppProperties appProperties,
                                   WalletLedgerRepository ledgerRepository,
                                   GenerationUsageLogRepository usageRepository,
                                   PaymentOrderRepository orderRepository,
                                   BillingReconciliationService reconciliationService) {
        this.appProperties = appProperties;
        this.ledgerRepository = ledgerRepository;
        this.usageRepository = usageRepository;
        this.orderRepository = orderRepository;
        this.reconciliationService = reconciliationService;
    }

    public BillingCsvExportService(AppProperties appProperties,
                                   WalletLedgerRepository ledgerRepository,
                                   GenerationUsageLogRepository usageRepository,
                                   PaymentOrderRepository orderRepository) {
        this(appProperties, ledgerRepository, usageRepository, orderRepository, null);
    }

    public String exportLedgerForUser(long userId, String type, String direction,
                                      LocalDateTime fromTime, LocalDateTime toTime) {
        return ledgerCsv(beforeId -> ledgerRepository.searchByUserBeforeId(
                userId, beforeId, type, direction, fromTime, toTime, PageRequest.of(0, CHUNK_SIZE)));
    }

    public String exportLedgerForAdmin(Long userId, String type, String direction,
                                       LocalDateTime fromTime, LocalDateTime toTime) {
        return ledgerCsv(beforeId -> ledgerRepository.searchAdminBeforeId(
                userId, beforeId, type, direction, fromTime, toTime, PageRequest.of(0, CHUNK_SIZE)));
    }

    public String exportUsageForUser(long userId, String status, String provider, String mode,
                                     LocalDateTime fromTime, LocalDateTime toTime) {
        return usageCsv(beforeId -> usageRepository.searchByUserBeforeId(
                userId, beforeId, status, provider, mode, fromTime, toTime, PageRequest.of(0, CHUNK_SIZE)));
    }

    public String exportUsageForAdmin(Long userId, String status, String provider, String mode,
                                      LocalDateTime fromTime, LocalDateTime toTime) {
        return usageCsv(beforeId -> usageRepository.searchAdminBeforeId(
                userId, beforeId, status, provider, mode, fromTime, toTime, PageRequest.of(0, CHUNK_SIZE)));
    }

    public String exportPaymentOrdersForUser(long userId, String status, String provider,
                                             LocalDateTime fromTime, LocalDateTime toTime) {
        return paymentCsv(beforeId -> orderRepository.searchByUserBeforeId(
                userId, beforeId, status, provider, fromTime, toTime, PageRequest.of(0, CHUNK_SIZE)));
    }

    public String exportPaymentOrdersForAdmin(Long userId, String status, String provider,
                                              LocalDateTime fromTime, LocalDateTime toTime) {
        return paymentCsv(beforeId -> orderRepository.searchAdminBeforeId(
                userId, beforeId, status, provider, fromTime, toTime, PageRequest.of(0, CHUNK_SIZE)));
    }

    public String exportReconciliationAnomaliesForAdmin(String type, Long userId) {
        if (reconciliationService == null) {
            throw new IllegalStateException("BillingReconciliationService 未配置");
        }
        StringBuilder csv = new StringBuilder(UTF8_BOM)
                .append("type,id,userId,referenceNo,status,points,amountCents,provider,occurredAt,anomalyKey,actionStatus,actionNote,actionUpdatedAt\n");
        Long cursor = null;
        int written = 0;
        int maxRows = exportMaxRows();
        boolean hasMore;
        do {
            BillingReconciliationService.AnomalyPage page = reconciliationService.anomalyDetails(
                    type, userId, cursor, ANOMALY_CHUNK_SIZE);
            for (BillingReconciliationService.AnomalyItem item : page.items()) {
                if (written >= maxRows) {
                    return csv.toString();
                }
                appendRow(csv, Arrays.asList(
                        page.type(),
                        item.id(),
                        item.userId(),
                        item.referenceNo(),
                        item.status(),
                        item.points(),
                        item.amountCents(),
                        item.provider(),
                        item.occurredAt(),
                        item.anomalyKey(),
                        item.actionStatus(),
                        item.actionNote(),
                        item.actionUpdatedAt()
                ));
                written++;
            }
            hasMore = page.hasMore();
            cursor = page.nextCursor();
        } while (hasMore && written < maxRows && cursor != null);
        return csv.toString();
    }

    private String ledgerCsv(Function<Long, Slice<WalletLedger>> pageLoader) {
        StringBuilder csv = new StringBuilder(UTF8_BOM)
                .append("id,userId,usageLogId,type,direction,pointsDelta,balanceBefore,balanceAfter,frozenBefore,frozenAfter,status,remark,createdAt\n");
        appendPaged(csv, pageLoader, WalletLedger::getId, ledger -> Arrays.asList(
                ledger.getId(),
                ledger.getUserId(),
                ledger.getUsageLogId(),
                ledger.getType(),
                ledger.getDirection(),
                ledger.getPointsDelta(),
                ledger.getBalanceBefore(),
                ledger.getBalanceAfter(),
                ledger.getFrozenBefore(),
                ledger.getFrozenAfter(),
                ledger.getStatus(),
                ledger.getRemark(),
                ledger.getCreatedAt()
        ));
        return csv.toString();
    }

    private String usageCsv(Function<Long, Slice<GenerationUsageLog>> pageLoader) {
        StringBuilder csv = new StringBuilder(UTF8_BOM)
                .append("id,userId,taskId,mode,agentId,provider,estimatedPoints,actualPoints,providerTaskId,providerRawCost,providerRawUnit,costSource,exchangeRate,status,errorMessage,startedAt,finishedAt\n");
        appendPaged(csv, pageLoader, GenerationUsageLog::getId, usage -> Arrays.asList(
                usage.getId(),
                usage.getUserId(),
                usage.getTaskId(),
                usage.getMode(),
                usage.getAgentId(),
                usage.getProvider(),
                usage.getEstimatedPoints(),
                usage.getActualPoints(),
                usage.getProviderTaskId(),
                usage.getProviderRawCost(),
                usage.getProviderRawUnit(),
                usage.getCostSource(),
                usage.getExchangeRate(),
                usage.getStatus(),
                usage.getErrorMessage(),
                usage.getStartedAt(),
                usage.getFinishedAt()
        ));
        return csv.toString();
    }

    private String paymentCsv(Function<Long, Slice<PaymentOrder>> pageLoader) {
        StringBuilder csv = new StringBuilder(UTF8_BOM)
                .append("id,orderNo,userId,points,amountCents,currency,provider,providerOrderNo,status,createdAt,updatedAt,paidAt\n");
        appendPaged(csv, pageLoader, PaymentOrder::getId, order -> Arrays.asList(
                order.getId(),
                order.getOrderNo(),
                order.getUserId(),
                order.getPoints(),
                order.getAmountCents(),
                order.getCurrency(),
                order.getProvider(),
                order.getProviderOrderNo(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getPaidAt()
        ));
        return csv.toString();
    }

    private <T> void appendPaged(StringBuilder csv,
                                Function<Long, Slice<T>> pageLoader,
                                Function<T, Long> idMapper,
                                Function<T, List<Object>> rowMapper) {
        Long beforeId = Long.MAX_VALUE;
        int written = 0;
        int maxRows = exportMaxRows();
        boolean hasNext;
        do {
            Slice<T> slice = pageLoader.apply(beforeId);
            Long nextBeforeId = null;
            for (T item : slice.getContent()) {
                if (written >= maxRows) {
                    return;
                }
                appendRow(csv, rowMapper.apply(item));
                nextBeforeId = idMapper.apply(item);
                written++;
            }
            hasNext = slice.hasNext();
            beforeId = nextBeforeId;
        } while (hasNext && written < maxRows && beforeId != null);
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

    private int exportMaxRows() {
        AppProperties.Billing billing = appProperties.getBilling();
        int configured = billing == null ? 10000 : billing.getExportMaxRows();
        return Math.max(1, configured);
    }
}
