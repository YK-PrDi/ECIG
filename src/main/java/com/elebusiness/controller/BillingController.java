package com.elebusiness.controller;

import com.elebusiness.model.entity.GenerationUsageLog;
import com.elebusiness.model.entity.PaymentOrder;
import com.elebusiness.model.entity.UserWallet;
import com.elebusiness.model.entity.WalletLedger;
import com.elebusiness.repository.GenerationUsageLogRepository;
import com.elebusiness.repository.PaymentOrderRepository;
import com.elebusiness.repository.UserWalletRepository;
import com.elebusiness.repository.WalletLedgerRepository;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingDailySummaryService;
import com.elebusiness.service.billing.BillingService;
import com.elebusiness.service.billing.PaymentOrderService;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class BillingController {

    private final BillingService billingService;
    private final WalletLedgerRepository ledgerRepository;
    private final GenerationUsageLogRepository usageLogRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentOrderService paymentOrderService;
    private final UserWalletRepository walletRepository;
    private final BillingDailySummaryService dailySummaryService;
    private final CurrentUserService currentUserService;
    private final com.elebusiness.repository.AppUserRepository appUserRepository;

    public BillingController(BillingService billingService,
                             WalletLedgerRepository ledgerRepository,
                             GenerationUsageLogRepository usageLogRepository,
                             PaymentOrderRepository paymentOrderRepository,
                             PaymentOrderService paymentOrderService,
                             UserWalletRepository walletRepository,
                             BillingDailySummaryService dailySummaryService,
                             CurrentUserService currentUserService,
                             com.elebusiness.repository.AppUserRepository appUserRepository) {
        this.billingService = billingService;
        this.ledgerRepository = ledgerRepository;
        this.usageLogRepository = usageLogRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentOrderService = paymentOrderService;
        this.walletRepository = walletRepository;
        this.dailySummaryService = dailySummaryService;
        this.currentUserService = currentUserService;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping("/api/billing/wallet")
    public Map<String, Object> wallet(HttpSession session) {
        long userId = currentUserService.requireUserId(session);
        UserWallet wallet = billingService.ensureWallet(userId);
        return walletMap(wallet);
    }

    public Map<String, Object> ledger(HttpSession session,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int limit) {
        return ledger(session, page, limit, null, null, null, null);
    }

    public Map<String, Object> ledger(HttpSession session,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(required = false) String direction,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        return ledger(session, page, limit, type, direction, from, to, null);
    }

    @GetMapping("/api/billing/ledger")
    public Map<String, Object> ledger(HttpSession session,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(required = false) String direction,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to,
                                      @RequestParam(required = false) Long cursor) {
        long userId = currentUserService.requireUserId(session);
        int safeLimit = normalizeLimit(limit);
        int safePage = cursor == null ? normalizePage(page) : 0;
        Long effectiveCursor = effectiveCursor(cursor, safePage);
        Slice<WalletLedger> slice = effectiveCursor == null
                ? ledgerRepository.searchByUser(
                    userId,
                    optionalText(type),
                    optionalText(direction),
                    parseTime(from),
                    parseTime(to),
                    PageRequest.of(safePage, safeLimit)
                )
                : ledgerRepository.searchByUserBeforeId(
                    userId,
                    effectiveCursor,
                    optionalText(type),
                    optionalText(direction),
                    parseTime(from),
                    parseTime(to),
                    PageRequest.of(0, safeLimit)
                );
        List<WalletLedger> content = slice.getContent();
        List<Map<String, Object>> items = content.stream()
                .map(this::ledgerMap)
                .toList();
        return pageMap(items, safePage, safeLimit, slice.hasNext(), nextLedgerCursor(content));
    }

    public Map<String, Object> usage(HttpSession session,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int limit) {
        return usage(session, page, limit, null, null, null, null, null);
    }

    public Map<String, Object> usage(HttpSession session,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String provider,
                                     @RequestParam(required = false) String mode,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        return usage(session, page, limit, status, provider, mode, from, to, null);
    }

    @GetMapping("/api/billing/usage")
    public Map<String, Object> usage(HttpSession session,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String provider,
                                     @RequestParam(required = false) String mode,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) Long cursor) {
        long userId = currentUserService.requireUserId(session);
        int safeLimit = normalizeLimit(limit);
        int safePage = cursor == null ? normalizePage(page) : 0;
        Long effectiveCursor = effectiveCursor(cursor, safePage);
        Slice<GenerationUsageLog> slice = effectiveCursor == null
                ? usageLogRepository.searchByUser(
                    userId,
                    optionalText(status),
                    optionalText(provider),
                    optionalText(mode),
                    parseTime(from),
                    parseTime(to),
                    PageRequest.of(safePage, safeLimit)
                )
                : usageLogRepository.searchByUserBeforeId(
                    userId,
                    effectiveCursor,
                    optionalText(status),
                    optionalText(provider),
                    optionalText(mode),
                    parseTime(from),
                    parseTime(to),
                    PageRequest.of(0, safeLimit)
                );
        List<GenerationUsageLog> content = slice.getContent();
        List<Map<String, Object>> items = content.stream()
                .map(this::usageMap)
                .toList();
        return pageMap(items, safePage, safeLimit, slice.hasNext(), nextUsageCursor(content));
    }

    public Map<String, Object> paymentOrders(HttpSession session,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int limit) {
        return paymentOrders(session, page, limit, null, null, null, null);
    }

    public Map<String, Object> paymentOrders(HttpSession session,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String provider,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        return paymentOrders(session, page, limit, status, provider, from, to, null);
    }

    @GetMapping("/api/billing/payment-orders")
    public Map<String, Object> paymentOrders(HttpSession session,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String provider,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) Long cursor) {
        long userId = currentUserService.requireUserId(session);
        int safeLimit = normalizeLimit(limit);
        int safePage = cursor == null ? normalizePage(page) : 0;
        Long effectiveCursor = effectiveCursor(cursor, safePage);
        Slice<PaymentOrder> slice = effectiveCursor == null
                ? paymentOrderRepository.searchByUser(
                    userId,
                    optionalText(status),
                    optionalText(provider),
                    parseTime(from),
                    parseTime(to),
                    PageRequest.of(safePage, safeLimit)
                )
                : paymentOrderRepository.searchByUserBeforeId(
                    userId,
                    effectiveCursor,
                    optionalText(status),
                    optionalText(provider),
                    parseTime(from),
                    parseTime(to),
                    PageRequest.of(0, safeLimit)
                );
        List<PaymentOrder> content = slice.getContent();
        List<Map<String, Object>> items = content.stream()
                .map(this::paymentOrderMap)
                .toList();
        return pageMap(items, safePage, safeLimit, slice.hasNext(), nextPaymentCursor(content));
    }

    @PostMapping("/api/billing/payment-orders")
    public ResponseEntity<Map<String, Object>> createPaymentOrder(@RequestBody Map<String, Object> body,
                                                                  HttpSession session) {
        long userId = currentUserService.requireUserId(session);
        long points = longValue(body, "points", 0);
        long amountCents = longValue(body, "amountCents", 0);
        if (points <= 0 || amountCents <= 0) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "points 和 amountCents 必须大于 0"));
        }
        String provider = stringValue(body, "provider", "manual");
        String idempotencyKey = stringValue(body, "idempotencyKey", "");
        PaymentOrder order = paymentOrderService.createRechargeOrder(userId, points, amountCents, provider, idempotencyKey);
        return ResponseEntity.ok(Map.of("success", true, "order", paymentOrderMap(order)));
    }

    @PostMapping("/api/billing/admin/payment-orders/{orderNo}/paid")
    public ResponseEntity<Map<String, Object>> markPaymentOrderPaid(@PathVariable String orderNo,
                                                                    @RequestBody Map<String, Object> body,
                                                                    HttpSession session) {
        currentUserService.requireAdmin(session);
        String providerOrderNo = stringValue(body, "providerOrderNo", "");
        long paidAmountCents = longValue(body, "paidAmountCents", 0);
        if (paidAmountCents <= 0) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "paidAmountCents 必须大于 0"));
        }
        PaymentOrder order = paymentOrderService.markPaid(orderNo, providerOrderNo, paidAmountCents);
        return ResponseEntity.ok(Map.of("success", true, "order", paymentOrderMap(order)));
    }

    @GetMapping("/api/billing/admin/wallet")
    public Map<String, Object> adminWallet(HttpSession session,
                                           @RequestParam long userId) {
        currentUserService.requireAdmin(session);
        return walletMap(billingService.ensureWallet(userId));
    }

    public Map<String, Object> adminLedger(HttpSession session,
                                           @RequestParam long userId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int limit) {
        return adminLedger(session, userId, page, limit, null, null, null, null);
    }

    public Map<String, Object> adminLedger(HttpSession session,
                                           @RequestParam(required = false) Long userId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int limit,
                                           @RequestParam(required = false) String type,
                                           @RequestParam(required = false) String direction,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        return adminLedger(session, userId, page, limit, type, direction, from, to, null);
    }

    @GetMapping("/api/billing/admin/ledger")
    public Map<String, Object> adminLedger(HttpSession session,
                                           @RequestParam(required = false) Long userId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int limit,
                                           @RequestParam(required = false) String type,
                                           @RequestParam(required = false) String direction,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(required = false) Long cursor) {
        currentUserService.requireAdmin(session);
        int safeLimit = normalizeLimit(limit);
        int safePage = cursor == null ? normalizePage(page) : 0;
        Long effectiveCursor = effectiveCursor(cursor, safePage);
        Slice<WalletLedger> slice = effectiveCursor == null
                ? ledgerRepository.searchAdmin(
                    userId,
                    optionalText(type),
                    optionalText(direction),
                    parseTime(from),
                    parseTime(to),
                    PageRequest.of(safePage, safeLimit)
                )
                : ledgerRepository.searchAdminBeforeId(
                    userId,
                    effectiveCursor,
                    optionalText(type),
                    optionalText(direction),
                    parseTime(from),
                    parseTime(to),
                    PageRequest.of(0, safeLimit)
                );
        List<WalletLedger> content = slice.getContent();
        List<Map<String, Object>> items = content.stream()
                .map(this::ledgerMap)
                .toList();
        return pageMap(items, safePage, safeLimit, slice.hasNext(), nextLedgerCursor(content));
    }

    public Map<String, Object> adminUsage(HttpSession session,
                                          @RequestParam long userId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int limit) {
        return adminUsage(session, userId, page, limit, null, null, null, null, null);
    }

    public Map<String, Object> adminUsage(HttpSession session,
                                          @RequestParam(required = false) Long userId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int limit,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String provider,
                                          @RequestParam(required = false) String mode,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        return adminUsage(session, userId, page, limit, status, provider, mode, from, to, null);
    }

    @GetMapping("/api/billing/admin/usage")
    public Map<String, Object> adminUsage(HttpSession session,
                                          @RequestParam(required = false) Long userId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int limit,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String provider,
                                          @RequestParam(required = false) String mode,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(required = false) Long cursor) {
        currentUserService.requireAdmin(session);
        int safeLimit = normalizeLimit(limit);
        int safePage = cursor == null ? normalizePage(page) : 0;
        Long effectiveCursor = effectiveCursor(cursor, safePage);
        Slice<GenerationUsageLog> slice = effectiveCursor == null
                ? usageLogRepository.searchAdmin(
                    userId,
                    optionalText(status),
                    optionalText(provider),
                    optionalText(mode),
                    parseTime(from),
                    parseTime(to),
                    PageRequest.of(safePage, safeLimit)
                )
                : usageLogRepository.searchAdminBeforeId(
                    userId,
                    effectiveCursor,
                    optionalText(status),
                    optionalText(provider),
                    optionalText(mode),
                    parseTime(from),
                    parseTime(to),
                    PageRequest.of(0, safeLimit)
                );
        List<GenerationUsageLog> content = slice.getContent();
        List<Map<String, Object>> items = content.stream()
                .map(this::usageMap)
                .toList();
        return pageMap(items, safePage, safeLimit, slice.hasNext(), nextUsageCursor(content));
    }

    public Map<String, Object> adminPaymentOrders(HttpSession session,
                                                  @RequestParam long userId,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int limit) {
        return adminPaymentOrders(session, userId, page, limit, null, null, null, null);
    }

    public Map<String, Object> adminPaymentOrders(HttpSession session,
                                                  @RequestParam(required = false) Long userId,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int limit,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String provider,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to) {
        return adminPaymentOrders(session, userId, page, limit, status, provider, from, to, null);
    }

    @GetMapping("/api/billing/admin/payment-orders")
    public Map<String, Object> adminPaymentOrders(HttpSession session,
                                                  @RequestParam(required = false) Long userId,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int limit,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String provider,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to,
                                                  @RequestParam(required = false) Long cursor) {
        currentUserService.requireAdmin(session);
        int safeLimit = normalizeLimit(limit);
        int safePage = cursor == null ? normalizePage(page) : 0;
        Long effectiveCursor = effectiveCursor(cursor, safePage);
        Slice<PaymentOrder> slice = effectiveCursor == null
                ? paymentOrderRepository.searchAdmin(
                    userId,
                    optionalText(status),
                    optionalText(provider),
                    parseTime(from),
                    parseTime(to),
                    PageRequest.of(safePage, safeLimit)
                )
                : paymentOrderRepository.searchAdminBeforeId(
                    userId,
                    effectiveCursor,
                    optionalText(status),
                    optionalText(provider),
                    parseTime(from),
                    parseTime(to),
                    PageRequest.of(0, safeLimit)
                );
        List<PaymentOrder> content = slice.getContent();
        List<Map<String, Object>> items = content.stream()
                .map(this::paymentOrderMap)
                .toList();
        return pageMap(items, safePage, safeLimit, slice.hasNext(), nextPaymentCursor(content));
    }

    @GetMapping("/api/billing/admin/summary")
    public Map<String, Object> adminSummary(HttpSession session,
                                            @RequestParam(required = false) Long userId,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        currentUserService.requireAdmin(session);
        LocalDateTime fromTime = parseTime(from);
        LocalDateTime toTime = parseTime(to);
        Object[] walletSummary = flattenAggregate(walletRepository.summarizeWallets(userId));
        BillingDailySummaryService.PeriodSummary snapshotSummary = dailySummaryService
                .findCompletePeriodSummary(userId, fromTime, toTime)
                .orElse(null);
        Object[] ledgerSummary = snapshotSummary == null
                ? flattenAggregate(ledgerRepository.summarizeLedger(userId, fromTime, toTime))
                : null;
        Object[] usageSummary = snapshotSummary == null
                ? flattenAggregate(usageLogRepository.summarizeUsage(userId, fromTime, toTime))
                : null;
        Object[] orderSummary = snapshotSummary == null
                ? flattenAggregate(paymentOrderRepository.summarizeOrders(userId, fromTime, toTime))
                : null;

        long balancePoints = aggregateLong(walletSummary, 0);
        long frozenPoints = aggregateLong(walletSummary, 1);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", userId);
        map.put("from", fromTime);
        map.put("to", toTime);
        map.put("summarySource", snapshotSummary == null ? "LIVE" : "SNAPSHOT");
        map.put("summaryDays", snapshotSummary == null ? 0L : snapshotSummary.summaryDays());
        map.put("balancePoints", balancePoints);
        map.put("frozenPoints", frozenPoints);
        map.put("availablePoints", balancePoints - frozenPoints);
        map.put("walletCount", aggregateLong(walletSummary, 2));
        putPeriodSummary(map, snapshotSummary, ledgerSummary, usageSummary, orderSummary);
        return map;
    }

    @PostMapping("/api/billing/admin/credit")
    public ResponseEntity<Map<String, Object>> credit(@RequestBody Map<String, Object> body,
                                                      HttpSession session) {
        com.elebusiness.service.auth.AuthService.AuthUser operator = currentUserService.requireAdmin(session);
        long userId = longValue(body, "userId", 0);
        long points = longValue(body, "points", 0);
        if (userId <= 0 || points <= 0) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "userId 和 points 必须大于 0"));
        }
        // 企业负责人只能给本企业成员充值；平台中控不受限
        if (!operator.isSuperadmin()) {
            var target = appUserRepository.findById(userId).orElse(null);
            if (target == null || !java.util.Objects.equals(operator.enterpriseId(), target.getEnterpriseId())) {
                return ResponseEntity.status(403).body(Map.of("success", false, "error", "只能给本企业成员充值"));
            }
        }
        String remark = stringValue(body, "remark", "");
        String idempotencyKey = stringValue(body, "idempotencyKey", "");
        if (idempotencyKey.isBlank()) {
            idempotencyKey = "admin-credit:" + userId + ":" + UUID.randomUUID();
        }
        WalletLedger ledger = billingService.creditPoints(userId, points, "ADMIN_RECHARGE", remark, idempotencyKey);
        return ResponseEntity.ok(Map.of("success", true, "ledger", ledgerMap(ledger)));
    }

    private Map<String, Object> walletMap(UserWallet wallet) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", wallet.getUserId());
        map.put("balancePoints", wallet.getBalancePoints());
        map.put("frozenPoints", wallet.getFrozenPoints());
        map.put("availablePoints", wallet.getBalancePoints() - wallet.getFrozenPoints());
        return map;
    }

    private Map<String, Object> ledgerMap(WalletLedger ledger) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", ledger.getId());
        map.put("userId", ledger.getUserId());
        map.put("usageLogId", ledger.getUsageLogId());
        map.put("type", ledger.getType());
        map.put("direction", ledger.getDirection());
        map.put("pointsDelta", ledger.getPointsDelta());
        map.put("balanceBefore", ledger.getBalanceBefore());
        map.put("balanceAfter", ledger.getBalanceAfter());
        map.put("frozenBefore", ledger.getFrozenBefore());
        map.put("frozenAfter", ledger.getFrozenAfter());
        map.put("status", ledger.getStatus());
        map.put("remark", ledger.getRemark());
        map.put("createdAt", ledger.getCreatedAt());
        return map;
    }

    private Map<String, Object> paymentOrderMap(PaymentOrder order) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", order.getId());
        map.put("orderNo", order.getOrderNo());
        map.put("userId", order.getUserId());
        map.put("points", order.getPoints());
        map.put("amountCents", order.getAmountCents());
        map.put("currency", order.getCurrency());
        map.put("provider", order.getProvider());
        map.put("providerOrderNo", order.getProviderOrderNo());
        map.put("status", order.getStatus());
        map.put("createdAt", order.getCreatedAt());
        map.put("updatedAt", order.getUpdatedAt());
        map.put("paidAt", order.getPaidAt());
        return map;
    }

    private Map<String, Object> usageMap(GenerationUsageLog usage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", usage.getId());
        map.put("userId", usage.getUserId());
        map.put("taskId", usage.getTaskId());
        map.put("mode", usage.getMode());
        map.put("agentId", usage.getAgentId());
        map.put("provider", usage.getProvider());
        map.put("estimatedPoints", usage.getEstimatedPoints());
        map.put("actualPoints", usage.getActualPoints());
        map.put("providerTaskId", usage.getProviderTaskId());
        map.put("providerRawCost", usage.getProviderRawCost());
        map.put("providerRawUnit", usage.getProviderRawUnit());
        map.put("costSource", usage.getCostSource());
        map.put("exchangeRate", usage.getExchangeRate());
        map.put("status", usage.getStatus());
        map.put("errorMessage", usage.getErrorMessage());
        map.put("startedAt", usage.getStartedAt());
        map.put("finishedAt", usage.getFinishedAt());
        return map;
    }

    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(200, limit));
    }

    private int normalizePage(int page) {
        return Math.max(0, page);
    }

    private Long effectiveCursor(Long cursor, int safePage) {
        if (cursor != null) {
            return cursor;
        }
        return safePage == 0 ? Long.MAX_VALUE : null;
    }

    private Long nextLedgerCursor(List<WalletLedger> items) {
        if (items == null || items.isEmpty()) return null;
        return items.get(items.size() - 1).getId();
    }

    private Long nextUsageCursor(List<GenerationUsageLog> items) {
        if (items == null || items.isEmpty()) return null;
        return items.get(items.size() - 1).getId();
    }

    private Long nextPaymentCursor(List<PaymentOrder> items) {
        if (items == null || items.isEmpty()) return null;
        return items.get(items.size() - 1).getId();
    }

    private String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() == 10) {
            return LocalDate.parse(trimmed).atStartOfDay();
        }
        return LocalDateTime.parse(trimmed);
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

    private void putPeriodSummary(Map<String, Object> map,
                                  BillingDailySummaryService.PeriodSummary snapshotSummary,
                                  Object[] ledgerSummary,
                                  Object[] usageSummary,
                                  Object[] orderSummary) {
        if (snapshotSummary != null) {
            map.put("inPoints", snapshotSummary.inPoints());
            map.put("outPoints", snapshotSummary.outPoints());
            map.put("frozenPointsDelta", snapshotSummary.frozenPointsDelta());
            map.put("releasedPoints", snapshotSummary.releasedPoints());
            map.put("ledgerCount", snapshotSummary.ledgerCount());
            map.put("usageCount", snapshotSummary.usageCount());
            map.put("succeededUsageCount", snapshotSummary.succeededUsageCount());
            map.put("failedUsageCount", snapshotSummary.failedUsageCount());
            map.put("runningUsageCount", snapshotSummary.runningUsageCount());
            map.put("actualPoints", snapshotSummary.actualPoints());
            map.put("estimatedPoints", snapshotSummary.estimatedPoints());
            map.put("orderCount", snapshotSummary.orderCount());
            map.put("paidOrderCount", snapshotSummary.paidOrderCount());
            map.put("pendingOrderCount", snapshotSummary.pendingOrderCount());
            map.put("paidAmountCents", snapshotSummary.paidAmountCents());
            map.put("pendingAmountCents", snapshotSummary.pendingAmountCents());
            return;
        }
        map.put("inPoints", aggregateLong(ledgerSummary, 0));
        map.put("outPoints", aggregateLong(ledgerSummary, 1));
        map.put("frozenPointsDelta", aggregateLong(ledgerSummary, 2));
        map.put("releasedPoints", aggregateLong(ledgerSummary, 3));
        map.put("ledgerCount", aggregateLong(ledgerSummary, 4));
        map.put("usageCount", aggregateLong(usageSummary, 0));
        map.put("succeededUsageCount", aggregateLong(usageSummary, 1));
        map.put("failedUsageCount", aggregateLong(usageSummary, 2));
        map.put("runningUsageCount", aggregateLong(usageSummary, 3));
        map.put("actualPoints", aggregateLong(usageSummary, 4));
        map.put("estimatedPoints", aggregateLong(usageSummary, 5));
        map.put("orderCount", aggregateLong(orderSummary, 0));
        map.put("paidOrderCount", aggregateLong(orderSummary, 1));
        map.put("pendingOrderCount", aggregateLong(orderSummary, 2));
        map.put("paidAmountCents", aggregateLong(orderSummary, 3));
        map.put("pendingAmountCents", aggregateLong(orderSummary, 4));
    }

    private Map<String, Object> pageMap(List<Map<String, Object>> items, int page, int limit, boolean hasMore) {
        return pageMap(items, page, limit, hasMore, null);
    }

    private Map<String, Object> pageMap(List<Map<String, Object>> items, int page, int limit, boolean hasMore, Long nextCursor) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("items", items);
        map.put("page", page);
        map.put("limit", limit);
        map.put("hasMore", hasMore);
        if (nextCursor != null) {
            map.put("nextCursor", nextCursor);
        }
        return map;
    }

    private long longValue(Map<String, Object> body, String key, long fallback) {
        if (body == null || body.get(key) == null) return fallback;
        Object value = body.get(key);
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String stringValue(Map<String, Object> body, String key, String fallback) {
        if (body == null || body.get(key) == null) return fallback;
        return String.valueOf(body.get(key));
    }
}
