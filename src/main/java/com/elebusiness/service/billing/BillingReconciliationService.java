package com.elebusiness.service.billing;

import com.elebusiness.model.entity.BillingReconciliationAnomalyAction;
import com.elebusiness.model.entity.GenerationUsageLog;
import com.elebusiness.model.entity.PaymentOrder;
import com.elebusiness.model.entity.WalletLedger;
import com.elebusiness.repository.BillingReconciliationAnomalyActionRepository;
import com.elebusiness.repository.GenerationUsageLogRepository;
import com.elebusiness.repository.PaymentOrderRepository;
import com.elebusiness.repository.UserWalletRepository;
import com.elebusiness.repository.WalletLedgerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BillingReconciliationService {

    private final UserWalletRepository walletRepository;
    private final WalletLedgerRepository ledgerRepository;
    private final GenerationUsageLogRepository usageRepository;
    private final PaymentOrderRepository orderRepository;
    private final BillingReconciliationAnomalyActionRepository actionRepository;

    @Autowired
    public BillingReconciliationService(UserWalletRepository walletRepository,
                                        WalletLedgerRepository ledgerRepository,
                                        GenerationUsageLogRepository usageRepository,
                                        PaymentOrderRepository orderRepository,
                                        BillingReconciliationAnomalyActionRepository actionRepository) {
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
        this.usageRepository = usageRepository;
        this.orderRepository = orderRepository;
        this.actionRepository = actionRepository;
    }

    public BillingReconciliationService(UserWalletRepository walletRepository,
                                        WalletLedgerRepository ledgerRepository,
                                        GenerationUsageLogRepository usageRepository,
                                        PaymentOrderRepository orderRepository) {
        this(walletRepository, ledgerRepository, usageRepository, orderRepository, null);
    }

    public Report reconcile(Long userId) {
        Object[] walletSummary = flattenAggregate(walletRepository.summarizeWallets(userId));
        long actualBalance = aggregateLong(walletSummary, 0);
        long actualFrozen = aggregateLong(walletSummary, 1);
        long expectedBalance = safeLong(ledgerRepository.sumPostedBalancePoints(userId));
        long expectedFrozen = safeLong(usageRepository.sumOpenEstimatedPoints(userId));
        long paidOrders = orderRepository.countPaidOrders(userId);
        long paymentLedgers = ledgerRepository.countPaymentRechargeLedgers(userId);
        long chargeableUsages = usageRepository.countChargeableSucceededUsages(userId);
        long chargeLedgers = ledgerRepository.countGenerationChargeLedgers(userId);

        List<Check> checks = new ArrayList<>();
        checks.add(check("WALLET_BALANCE", "钱包余额", expectedBalance, actualBalance));
        checks.add(check("WALLET_FROZEN", "冻结积分", expectedFrozen, actualFrozen));
        checks.add(check("PAID_ORDER_LEDGER", "已支付订单入账流水", paidOrders, paymentLedgers));
        checks.add(check("USAGE_CHARGE_LEDGER", "成功调用扣费流水", chargeableUsages, chargeLedgers));

        boolean healthy = checks.stream().allMatch(Check::ok);
        return new Report(userId, healthy, checks);
    }

    public AnomalyPage anomalyDetails(String type, Long userId, Long cursor, int limit) {
        String safeType = type == null ? "" : type.trim().toUpperCase();
        int safeLimit = Math.max(1, Math.min(200, limit));
        PageRequest pageRequest = PageRequest.of(0, safeLimit);
        return switch (safeType) {
            case "PAID_ORDER_LEDGER" -> paidOrderLedgerAnomalies(userId, cursor, safeLimit, pageRequest);
            case "USAGE_CHARGE_LEDGER" -> usageChargeLedgerAnomalies(userId, cursor, safeLimit, pageRequest);
            case "PAYMENT_LEDGER_ORDER" -> paymentLedgerOrderAnomalies(userId, cursor, safeLimit, pageRequest);
            case "CHARGE_LEDGER_USAGE" -> chargeLedgerUsageAnomalies(userId, cursor, safeLimit, pageRequest);
            default -> throw new IllegalArgumentException("不支持的对账异常类型: " + safeType);
        };
    }

    private AnomalyPage paidOrderLedgerAnomalies(Long userId, Long cursor, int limit, PageRequest pageRequest) {
        Slice<PaymentOrder> slice = orderRepository.findPaidOrdersMissingRechargeLedger(userId, cursor, pageRequest);
        List<AnomalyItem> items = slice.getContent().stream()
                .map(order -> new AnomalyItem(
                        order.getId(),
                        order.getUserId(),
                        order.getOrderNo(),
                        order.getStatus(),
                        order.getPoints(),
                        order.getAmountCents(),
                        order.getProvider(),
                        order.getPaidAt() == null ? order.getCreatedAt() : order.getPaidAt()
                ))
                .toList();
        return new AnomalyPage("PAID_ORDER_LEDGER", userId, attachActionSummaries("PAID_ORDER_LEDGER", items),
                limit, slice.hasNext(), nextCursor(items));
    }

    private AnomalyPage usageChargeLedgerAnomalies(Long userId, Long cursor, int limit, PageRequest pageRequest) {
        Slice<GenerationUsageLog> slice = usageRepository.findChargeableSucceededUsagesMissingChargeLedger(userId, cursor, pageRequest);
        List<AnomalyItem> items = slice.getContent().stream()
                .map(usage -> new AnomalyItem(
                        usage.getId(),
                        usage.getUserId(),
                        usage.getTaskId(),
                        usage.getStatus(),
                        usage.getActualPoints(),
                        0L,
                        usage.getProvider(),
                        usage.getFinishedAt() == null ? usage.getStartedAt() : usage.getFinishedAt()
                ))
                .toList();
        return new AnomalyPage("USAGE_CHARGE_LEDGER", userId, attachActionSummaries("USAGE_CHARGE_LEDGER", items),
                limit, slice.hasNext(), nextCursor(items));
    }

    private AnomalyPage paymentLedgerOrderAnomalies(Long userId, Long cursor, int limit, PageRequest pageRequest) {
        Slice<WalletLedger> slice = ledgerRepository.findPaymentRechargeLedgersMissingPaidOrder(userId, cursor, pageRequest);
        List<AnomalyItem> items = slice.getContent().stream()
                .map(ledger -> new AnomalyItem(
                        ledger.getId(),
                        ledger.getUserId(),
                        paymentOrderReference(ledger.getIdempotencyKey()),
                        ledger.getStatus(),
                        Math.abs(ledger.getPointsDelta()),
                        0L,
                        ledger.getType(),
                        ledger.getCreatedAt()
                ))
                .toList();
        return new AnomalyPage("PAYMENT_LEDGER_ORDER", userId, attachActionSummaries("PAYMENT_LEDGER_ORDER", items),
                limit, slice.hasNext(), nextCursor(items));
    }

    private AnomalyPage chargeLedgerUsageAnomalies(Long userId, Long cursor, int limit, PageRequest pageRequest) {
        Slice<WalletLedger> slice = ledgerRepository.findGenerationChargeLedgersMissingSucceededUsage(userId, cursor, pageRequest);
        List<AnomalyItem> items = slice.getContent().stream()
                .map(ledger -> new AnomalyItem(
                        ledger.getId(),
                        ledger.getUserId(),
                        ledger.getUsageLogId() == null ? ledger.getIdempotencyKey() : String.valueOf(ledger.getUsageLogId()),
                        ledger.getStatus(),
                        Math.abs(ledger.getPointsDelta()),
                        0L,
                        ledger.getType(),
                        ledger.getCreatedAt()
                ))
                .toList();
        return new AnomalyPage("CHARGE_LEDGER_USAGE", userId, attachActionSummaries("CHARGE_LEDGER_USAGE", items),
                limit, slice.hasNext(), nextCursor(items));
    }

    private List<AnomalyItem> attachActionSummaries(String type, List<AnomalyItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> keys = items.stream()
                .map(item -> anomalyKey(type, item.id()))
                .toList();
        Map<String, BillingReconciliationAnomalyAction> actions = actionRepository == null
                ? Map.of()
                : actionRepository.findByAnomalyKeyIn(keys).stream()
                        .collect(Collectors.toMap(BillingReconciliationAnomalyAction::getAnomalyKey,
                                Function.identity(), (left, right) -> left));
        return items.stream()
                .map(item -> item.withActionSummary(type, actions.get(anomalyKey(type, item.id()))))
                .toList();
    }

    private String anomalyKey(String type, Long sourceId) {
        return type + ":" + sourceId;
    }

    private String paymentOrderReference(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return "";
        }
        String prefix = "payment:";
        return idempotencyKey.startsWith(prefix) ? idempotencyKey.substring(prefix.length()) : idempotencyKey;
    }

    private Long nextCursor(List<AnomalyItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return items.get(items.size() - 1).id();
    }

    private Check check(String code, String name, long expected, long actual) {
        long difference = Math.abs(actual - expected);
        return new Check(code, name, expected, actual, difference, difference == 0L);
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private Object[] flattenAggregate(Object aggregate) {
        if (aggregate == null) {
            return new Object[0];
        }
        if (aggregate instanceof Object[] values) {
            if (values.length == 1 && values[0] instanceof Object[] nested) {
                return nested;
            }
            return values;
        }
        return new Object[]{aggregate};
    }

    private long aggregateLong(Object[] values, int index) {
        if (values == null || index < 0 || index >= values.length || values[index] == null) {
            return 0L;
        }
        Object value = values[index];
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public record Report(Long userId, boolean healthy, List<Check> checks) {}

    public record Check(String code, String name, long expected, long actual, long difference, boolean ok) {}

    public record AnomalyPage(String type, Long userId, List<AnomalyItem> items,
                              int limit, boolean hasMore, Long nextCursor) {}

    public record AnomalyItem(Long id, Long userId, String referenceNo, String status,
                              long points, long amountCents, String provider, LocalDateTime occurredAt,
                              String anomalyKey, String actionStatus, String actionNote,
                              LocalDateTime actionUpdatedAt) {

        public AnomalyItem(Long id, Long userId, String referenceNo, String status,
                           long points, long amountCents, String provider, LocalDateTime occurredAt) {
            this(id, userId, referenceNo, status, points, amountCents, provider, occurredAt,
                    null, null, null, null);
        }

        AnomalyItem withActionSummary(String type, BillingReconciliationAnomalyAction action) {
            return new AnomalyItem(id, userId, referenceNo, status, points, amountCents, provider, occurredAt,
                    type + ":" + id,
                    action == null ? null : action.getStatus(),
                    action == null ? null : action.getNote(),
                    action == null ? null : action.getUpdatedAt());
        }
    }
}
