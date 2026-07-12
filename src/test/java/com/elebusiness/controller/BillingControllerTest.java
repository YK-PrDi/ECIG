package com.elebusiness.controller;

import com.elebusiness.model.entity.GenerationUsageLog;
import com.elebusiness.model.entity.PaymentOrder;
import com.elebusiness.model.entity.UserWallet;
import com.elebusiness.model.entity.WalletLedger;
import com.elebusiness.repository.GenerationUsageLogRepository;
import com.elebusiness.repository.PaymentOrderRepository;
import com.elebusiness.repository.UserWalletRepository;
import com.elebusiness.repository.WalletLedgerRepository;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingDailySummaryService;
import com.elebusiness.service.billing.BillingService;
import com.elebusiness.service.billing.PaymentOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingControllerTest {

    @Test
    void walletReturnsOnlyCurrentUsersBalanceSnapshot() {
        BillingService billingService = mock(BillingService.class);
        UserWallet wallet = new UserWallet();
        wallet.setUserId(1001L);
        wallet.setBalancePoints(120);
        wallet.setFrozenPoints(30);
        when(billingService.ensureWallet(1001L)).thenReturn(wallet);

        BillingController controller = controller(billingService, mock(WalletLedgerRepository.class),
                mock(GenerationUsageLogRepository.class), mock(PaymentOrderRepository.class), mock(PaymentOrderService.class));

        Map<String, Object> body = controller.wallet(userSession(1001L, "USER"));

        assertEquals(1001L, body.get("userId"));
        assertEquals(120L, body.get("balancePoints"));
        assertEquals(30L, body.get("frozenPoints"));
        assertEquals(90L, body.get("availablePoints"));
    }

    @Test
    void ledgerAndUsageAreScopedToCurrentUser() {
        BillingService billingService = mock(BillingService.class);
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageLogRepository = mock(GenerationUsageLogRepository.class);
        WalletLedger ledger = new WalletLedger();
        ledger.setUserId(1001L);
        ledger.setType("GENERATION_CHARGE");
        GenerationUsageLog usage = new GenerationUsageLog();
        usage.setUserId(1001L);
        usage.setStatus("SUCCEEDED");
        when(ledgerRepository.searchByUser(eq(1001L), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(1, 20))))
                .thenReturn(new SliceImpl<>(List.of(ledger), PageRequest.of(1, 20), true));
        when(usageLogRepository.searchByUser(eq(1001L), isNull(), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(1, 20))))
                .thenReturn(new SliceImpl<>(List.of(usage), PageRequest.of(1, 20), false));
        BillingController controller = controller(billingService, ledgerRepository, usageLogRepository,
                mock(PaymentOrderRepository.class), mock(PaymentOrderService.class));

        Map<String, Object> ledgerPage = controller.ledger(userSession(1001L, "USER"), 1, 20);
        Map<String, Object> usagePage = controller.usage(userSession(1001L, "USER"), 1, 20);

        assertEquals(1, ((List<?>) ledgerPage.get("items")).size());
        assertEquals(1, ledgerPage.get("page"));
        assertEquals(20, ledgerPage.get("limit"));
        assertEquals(true, ledgerPage.get("hasMore"));
        assertEquals(1, ((List<?>) usagePage.get("items")).size());
        assertEquals(false, usagePage.get("hasMore"));
        verify(ledgerRepository).searchByUser(eq(1001L), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(1, 20)));
        verify(usageLogRepository).searchByUser(eq(1001L), isNull(), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(1, 20)));
    }

    @Test
    void userBillingListsSupportScopedFilters() {
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageLogRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository paymentOrderRepository = mock(PaymentOrderRepository.class);
        BillingController controller = controller(mock(BillingService.class), ledgerRepository, usageLogRepository,
                paymentOrderRepository, mock(PaymentOrderService.class), mock(UserWalletRepository.class));
        LocalDateTime from = LocalDateTime.of(2026, 7, 10, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 7, 11, 0, 0);
        Pageable pageable = PageRequest.of(0, 20);

        when(ledgerRepository.searchByUserBeforeId(eq(1001L), eq(Long.MAX_VALUE), eq("GENERATION_CHARGE"), eq("OUT"),
                eq(from), eq(to), eq(pageable))).thenReturn(new SliceImpl<>(List.of(new WalletLedger()), pageable, false));
        when(usageLogRepository.searchByUserBeforeId(eq(1001L), eq(Long.MAX_VALUE), eq("SUCCEEDED"), eq("liblib"), eq("custom"),
                eq(from), eq(to), eq(pageable))).thenReturn(new SliceImpl<>(List.of(new GenerationUsageLog()), pageable, false));
        when(paymentOrderRepository.searchByUserBeforeId(eq(1001L), eq(Long.MAX_VALUE), eq("PAID"), eq("manual"),
                eq(from), eq(to), eq(pageable))).thenReturn(new SliceImpl<>(List.of(paymentOrder(1001L)), pageable, false));

        MockHttpSession session = userSession(1001L, "USER");
        assertEquals(1, ((List<?>) controller.ledger(session, 0, 20, "GENERATION_CHARGE", "OUT",
                "2026-07-10T00:00:00", "2026-07-11T00:00:00").get("items")).size());
        assertEquals(1, ((List<?>) controller.usage(session, 0, 20, "SUCCEEDED", "liblib", "custom",
                "2026-07-10T00:00:00", "2026-07-11T00:00:00").get("items")).size());
        assertEquals(1, ((List<?>) controller.paymentOrders(session, 0, 20, "PAID", "manual",
                "2026-07-10T00:00:00", "2026-07-11T00:00:00").get("items")).size());

        verify(ledgerRepository).searchByUserBeforeId(1001L, Long.MAX_VALUE, "GENERATION_CHARGE", "OUT", from, to, pageable);
        verify(usageLogRepository).searchByUserBeforeId(1001L, Long.MAX_VALUE, "SUCCEEDED", "liblib", "custom", from, to, pageable);
        verify(paymentOrderRepository).searchByUserBeforeId(1001L, Long.MAX_VALUE, "PAID", "manual", from, to, pageable);
    }

    @Test
    void userBillingListsUseCursorQueriesWhenCursorIsPresent() {
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageLogRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository paymentOrderRepository = mock(PaymentOrderRepository.class);
        BillingController controller = controller(mock(BillingService.class), ledgerRepository, usageLogRepository,
                paymentOrderRepository, mock(PaymentOrderService.class), mock(UserWalletRepository.class));
        Pageable firstPage = PageRequest.of(0, 20);
        WalletLedger ledger = new WalletLedger();
        ledger.setId(88L);
        GenerationUsageLog usage = new GenerationUsageLog();
        usage.setId(77L);
        PaymentOrder order = paymentOrder(1001L);
        order.setId(66L);

        when(ledgerRepository.searchByUserBeforeId(eq(1001L), eq(120L), eq("GENERATION_CHARGE"), eq("OUT"),
                isNull(), isNull(), eq(firstPage))).thenReturn(new SliceImpl<>(List.of(ledger), firstPage, true));
        when(usageLogRepository.searchByUserBeforeId(eq(1001L), eq(120L), eq("SUCCEEDED"), eq("liblib"), eq("custom"),
                isNull(), isNull(), eq(firstPage))).thenReturn(new SliceImpl<>(List.of(usage), firstPage, true));
        when(paymentOrderRepository.searchByUserBeforeId(eq(1001L), eq(120L), eq("PAID"), eq("manual"),
                isNull(), isNull(), eq(firstPage))).thenReturn(new SliceImpl<>(List.of(order), firstPage, true));

        MockHttpSession session = userSession(1001L, "USER");
        Map<String, Object> ledgerPage = controller.ledger(session, 9, 20, "GENERATION_CHARGE", "OUT", null, null, 120L);
        Map<String, Object> usagePage = controller.usage(session, 9, 20, "SUCCEEDED", "liblib", "custom", null, null, 120L);
        Map<String, Object> paymentPage = controller.paymentOrders(session, 9, 20, "PAID", "manual", null, null, 120L);

        assertEquals(88L, ledgerPage.get("nextCursor"));
        assertEquals(77L, usagePage.get("nextCursor"));
        assertEquals(66L, paymentPage.get("nextCursor"));
        assertEquals(0, ledgerPage.get("page"));
        verify(ledgerRepository).searchByUserBeforeId(1001L, 120L, "GENERATION_CHARGE", "OUT", null, null, firstPage);
        verify(usageLogRepository).searchByUserBeforeId(1001L, 120L, "SUCCEEDED", "liblib", "custom", null, null, firstPage);
        verify(paymentOrderRepository).searchByUserBeforeId(1001L, 120L, "PAID", "manual", null, null, firstPage);
    }

    @Test
    void usageListIncludesProviderCostMetadata() {
        GenerationUsageLogRepository usageLogRepository = mock(GenerationUsageLogRepository.class);
        BillingController controller = controller(mock(BillingService.class), mock(WalletLedgerRepository.class),
                usageLogRepository, mock(PaymentOrderRepository.class), mock(PaymentOrderService.class));
        GenerationUsageLog usage = new GenerationUsageLog();
        usage.setId(77L);
        usage.setUserId(1001L);
        usage.setTaskId("task-77");
        usage.setMode("custom");
        usage.setAgentId("liblib-lora");
        usage.setProvider("liblib");
        usage.setEstimatedPoints(30);
        usage.setActualPoints(18);
        usage.setProviderTaskId("liblib-generate-001");
        usage.setProviderRawCost(new BigDecimal("12.500000"));
        usage.setProviderRawUnit("LIBLIB_POINTS");
        usage.setCostSource("PROVIDER_REPORTED");
        usage.setExchangeRate(new BigDecimal("1.440000"));
        usage.setStatus("SUCCEEDED");
        when(usageLogRepository.searchByUserBeforeId(eq(1001L), eq(Long.MAX_VALUE), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(PageRequest.of(0, 20))))
                .thenReturn(new SliceImpl<>(List.of(usage), PageRequest.of(0, 20), false));

        Map<String, Object> page = controller.usage(userSession(1001L, "USER"), 0, 20);
        Map<?, ?> item = (Map<?, ?>) ((List<?>) page.get("items")).get(0);

        assertEquals("liblib-generate-001", item.get("providerTaskId"));
        assertEquals(new BigDecimal("12.500000"), item.get("providerRawCost"));
        assertEquals("LIBLIB_POINTS", item.get("providerRawUnit"));
        assertEquals("PROVIDER_REPORTED", item.get("costSource"));
        assertEquals(new BigDecimal("1.440000"), item.get("exchangeRate"));
    }

    @Test
    void onlyAdminCanCreditWallet() {
        BillingService billingService = mock(BillingService.class);
        BillingController controller = controller(billingService, mock(WalletLedgerRepository.class),
                mock(GenerationUsageLogRepository.class), mock(PaymentOrderRepository.class), mock(PaymentOrderService.class));

        assertThrows(ResponseStatusException.class, () -> controller.credit(
                Map.<String, Object>of("userId", 2002L, "points", 100),
                userSession(1001L, "USER")));

        WalletLedger ledger = new WalletLedger();
        ledger.setUserId(2002L);
        ledger.setPointsDelta(100);
        when(billingService.creditPoints(2002L, 100L, "ADMIN_RECHARGE", "manual", "key-1")).thenReturn(ledger);
        ResponseEntity<Map<String, Object>> response = controller.credit(
                Map.<String, Object>of("userId", 2002L, "points", 100, "remark", "manual", "idempotencyKey", "key-1"),
                userSession(1L, "ADMIN"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        verify(billingService).creditPoints(2002L, 100L, "ADMIN_RECHARGE", "manual", "key-1");
    }

    @Test
    void currentUserCanCreateAndListOwnPaymentOrders() {
        BillingService billingService = mock(BillingService.class);
        PaymentOrderRepository paymentOrderRepository = mock(PaymentOrderRepository.class);
        PaymentOrderService paymentOrderService = mock(PaymentOrderService.class);
        BillingController controller = controller(billingService, mock(WalletLedgerRepository.class),
                mock(GenerationUsageLogRepository.class), paymentOrderRepository, paymentOrderService);

        PaymentOrder order = paymentOrder(1001L);
        when(paymentOrderService.createRechargeOrder(1001L, 100L, 19900L, "manual", "client-req-1"))
                .thenReturn(order);
        ResponseEntity<Map<String, Object>> created = controller.createPaymentOrder(
                Map.<String, Object>of("points", 100, "amountCents", 19900, "provider", "manual",
                        "idempotencyKey", "client-req-1", "userId", 9999),
                userSession(1001L, "USER"));

        assertEquals(200, created.getStatusCode().value());
        assertEquals(true, created.getBody().get("success"));
        Map<?, ?> orderBody = (Map<?, ?>) created.getBody().get("order");
        assertEquals(1001L, orderBody.get("userId"));
        verify(paymentOrderService).createRechargeOrder(1001L, 100L, 19900L, "manual", "client-req-1");

        when(paymentOrderRepository.searchByUserBeforeId(eq(1001L), eq(Long.MAX_VALUE), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(0, 20))))
                .thenReturn(new SliceImpl<>(List.of(order), PageRequest.of(0, 20), false));
        Map<String, Object> listed = controller.paymentOrders(userSession(1001L, "USER"), 0, 20);
        assertEquals(1, ((List<?>) listed.get("items")).size());
        assertEquals(0, listed.get("page"));
        assertEquals(false, listed.get("hasMore"));
        verify(paymentOrderRepository).searchByUserBeforeId(eq(1001L), eq(Long.MAX_VALUE), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(0, 20)));
    }

    @Test
    void onlyAdminCanMarkPaymentOrderPaid() {
        PaymentOrderService paymentOrderService = mock(PaymentOrderService.class);
        BillingController controller = controller(mock(BillingService.class), mock(WalletLedgerRepository.class),
                mock(GenerationUsageLogRepository.class), mock(PaymentOrderRepository.class), paymentOrderService);

        assertThrows(ResponseStatusException.class, () -> controller.markPaymentOrderPaid(
                "R202607100001",
                Map.<String, Object>of("providerOrderNo", "wx-123", "paidAmountCents", 19900),
                userSession(1001L, "USER")));

        PaymentOrder order = paymentOrder(2002L);
        when(paymentOrderService.markPaid("R202607100001", "wx-123", 19900L)).thenReturn(order);
        ResponseEntity<Map<String, Object>> response = controller.markPaymentOrderPaid(
                "R202607100001",
                Map.<String, Object>of("providerOrderNo", "wx-123", "paidAmountCents", 19900),
                userSession(1L, "ADMIN"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        verify(paymentOrderService).markPaid("R202607100001", "wx-123", 19900L);
    }

    @Test
    void onlyAdminCanQueryAnyUsersBillingData() {
        BillingService billingService = mock(BillingService.class);
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageLogRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository paymentOrderRepository = mock(PaymentOrderRepository.class);
        BillingController controller = controller(billingService, ledgerRepository, usageLogRepository,
                paymentOrderRepository, mock(PaymentOrderService.class));

        assertThrows(ResponseStatusException.class,
                () -> controller.adminWallet(userSession(1001L, "USER"), 2002L));

        UserWallet wallet = new UserWallet();
        wallet.setUserId(2002L);
        wallet.setBalancePoints(500L);
        wallet.setFrozenPoints(120L);
        when(billingService.ensureWallet(2002L)).thenReturn(wallet);
        WalletLedger ledger = new WalletLedger();
        ledger.setUserId(2002L);
        ledger.setType("PAYMENT_RECHARGE");
        GenerationUsageLog usage = new GenerationUsageLog();
        usage.setUserId(2002L);
        usage.setStatus("SUCCEEDED");
        PaymentOrder order = paymentOrder(2002L);
        when(ledgerRepository.searchAdminBeforeId(eq(2002L), eq(Long.MAX_VALUE), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(0, 20))))
                .thenReturn(new SliceImpl<>(List.of(ledger), PageRequest.of(0, 20), false));
        when(usageLogRepository.searchAdminBeforeId(eq(2002L), eq(Long.MAX_VALUE), isNull(), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(0, 20))))
                .thenReturn(new SliceImpl<>(List.of(usage), PageRequest.of(0, 20), false));
        when(paymentOrderRepository.searchAdminBeforeId(eq(2002L), eq(Long.MAX_VALUE), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(0, 20))))
                .thenReturn(new SliceImpl<>(List.of(order), PageRequest.of(0, 20), false));

        MockHttpSession admin = userSession(1L, "ADMIN");
        assertEquals(2002L, controller.adminWallet(admin, 2002L).get("userId"));
        assertEquals(1, ((List<?>) controller.adminLedger(admin, 2002L, 0, 20).get("items")).size());
        assertEquals(1, ((List<?>) controller.adminUsage(admin, 2002L, 0, 20).get("items")).size());
        assertEquals(1, ((List<?>) controller.adminPaymentOrders(admin, 2002L, 0, 20).get("items")).size());

        verify(ledgerRepository).searchAdminBeforeId(eq(2002L), eq(Long.MAX_VALUE), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(0, 20)));
        verify(usageLogRepository).searchAdminBeforeId(eq(2002L), eq(Long.MAX_VALUE), isNull(), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(0, 20)));
        verify(paymentOrderRepository).searchAdminBeforeId(eq(2002L), eq(Long.MAX_VALUE), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(0, 20)));
    }

    @Test
    void onlyAdminCanReadBillingSummary() {
        UserWalletRepository walletRepository = mock(UserWalletRepository.class);
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageLogRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository paymentOrderRepository = mock(PaymentOrderRepository.class);
        BillingController controller = controller(mock(BillingService.class), ledgerRepository, usageLogRepository,
                paymentOrderRepository, mock(PaymentOrderService.class), walletRepository);
        LocalDateTime from = LocalDateTime.of(2026, 7, 10, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 7, 11, 0, 0);

        assertThrows(ResponseStatusException.class,
                () -> controller.adminSummary(userSession(1001L, "USER"), null, null, null));

        when(walletRepository.summarizeWallets(eq(2002L))).thenReturn(new Object[]{500L, 120L, 1L});
        when(ledgerRepository.summarizeLedger(eq(2002L), eq(from), eq(to)))
                .thenReturn(new Object[]{300L, 80L, 40L, 20L, 6L});
        when(usageLogRepository.summarizeUsage(eq(2002L), eq(from), eq(to)))
                .thenReturn(new Object[]{10L, 7L, 2L, 1L, 90L, 110L});
        when(paymentOrderRepository.summarizeOrders(eq(2002L), eq(from), eq(to)))
                .thenReturn(new Object[]{4L, 3L, 1L, 39900L, 19900L});

        Map<String, Object> summary = controller.adminSummary(userSession(1L, "ADMIN"), 2002L,
                "2026-07-10T00:00:00", "2026-07-11T00:00:00");

        assertEquals(2002L, summary.get("userId"));
        assertEquals(500L, summary.get("balancePoints"));
        assertEquals(120L, summary.get("frozenPoints"));
        assertEquals(380L, summary.get("availablePoints"));
        assertEquals(1L, summary.get("walletCount"));
        assertEquals(300L, summary.get("inPoints"));
        assertEquals(80L, summary.get("outPoints"));
        assertEquals(40L, summary.get("frozenPointsDelta"));
        assertEquals(20L, summary.get("releasedPoints"));
        assertEquals(10L, summary.get("usageCount"));
        assertEquals(7L, summary.get("succeededUsageCount"));
        assertEquals(3L, summary.get("paidOrderCount"));
        assertEquals(39900L, summary.get("paidAmountCents"));
    }

    @Test
    void adminSummaryUsesDailySnapshotsForCompleteWholeDayRangesWhenAvailable() {
        UserWalletRepository walletRepository = mock(UserWalletRepository.class);
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageLogRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository paymentOrderRepository = mock(PaymentOrderRepository.class);
        BillingDailySummaryService dailySummaryService = mock(BillingDailySummaryService.class);
        BillingController controller = controller(mock(BillingService.class), ledgerRepository, usageLogRepository,
                paymentOrderRepository, mock(PaymentOrderService.class), walletRepository, dailySummaryService);
        LocalDateTime from = LocalDateTime.of(2026, 7, 10, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 7, 12, 0, 0);
        BillingDailySummaryService.PeriodSummary period = new BillingDailySummaryService.PeriodSummary(
                2L, 300L, 80L, 40L, 20L, 6L,
                10L, 7L, 2L, 1L, 90L, 110L,
                4L, 3L, 1L, 39900L, 19900L);

        when(walletRepository.summarizeWallets(isNull())).thenReturn(new Object[]{500L, 120L, 3L});
        when(dailySummaryService.findCompletePeriodSummary(isNull(), eq(from), eq(to))).thenReturn(Optional.of(period));

        Map<String, Object> summary = controller.adminSummary(userSession(1L, "ADMIN"), null,
                "2026-07-10", "2026-07-12");

        assertEquals("SNAPSHOT", summary.get("summarySource"));
        assertEquals(2L, summary.get("summaryDays"));
        assertEquals(300L, summary.get("inPoints"));
        assertEquals(80L, summary.get("outPoints"));
        assertEquals(6L, summary.get("ledgerCount"));
        assertEquals(10L, summary.get("usageCount"));
        assertEquals(3L, summary.get("paidOrderCount"));
        verify(ledgerRepository, never()).summarizeLedger(any(), any(), any());
        verify(usageLogRepository, never()).summarizeUsage(any(), any(), any());
        verify(paymentOrderRepository, never()).summarizeOrders(any(), any(), any());
    }

    @Test
    void adminBillingQueriesAllowOptionalUserScopeForOperations() {
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageLogRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository paymentOrderRepository = mock(PaymentOrderRepository.class);
        BillingController controller = controller(mock(BillingService.class), ledgerRepository, usageLogRepository,
                paymentOrderRepository, mock(PaymentOrderService.class), mock(UserWalletRepository.class));
        Pageable pageable = PageRequest.of(0, 20);

        when(ledgerRepository.searchAdminBeforeId(isNull(), eq(Long.MAX_VALUE), eq("PAYMENT_RECHARGE"), isNull(), isNull(), isNull(), eq(pageable)))
                .thenReturn(new SliceImpl<>(List.of(new WalletLedger()), pageable, false));
        when(usageLogRepository.searchAdminBeforeId(isNull(), eq(Long.MAX_VALUE), eq("FAILED"), isNull(), isNull(), isNull(), isNull(), eq(pageable)))
                .thenReturn(new SliceImpl<>(List.of(new GenerationUsageLog()), pageable, false));
        when(paymentOrderRepository.searchAdminBeforeId(isNull(), eq(Long.MAX_VALUE), eq("PENDING"), isNull(), isNull(), isNull(), eq(pageable)))
                .thenReturn(new SliceImpl<>(List.of(paymentOrder(2002L)), pageable, false));

        MockHttpSession admin = userSession(1L, "ADMIN");
        assertEquals(1, ((List<?>) controller.adminLedger(admin, null, 0, 20,
                "PAYMENT_RECHARGE", null, null, null).get("items")).size());
        assertEquals(1, ((List<?>) controller.adminUsage(admin, null, 0, 20,
                "FAILED", null, null, null, null).get("items")).size());
        assertEquals(1, ((List<?>) controller.adminPaymentOrders(admin, null, 0, 20,
                "PENDING", null, null, null).get("items")).size());
    }

    @Test
    void adminBillingListsUseCursorQueriesWhenCursorIsPresent() {
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        BillingController controller = controller(mock(BillingService.class), ledgerRepository,
                mock(GenerationUsageLogRepository.class), mock(PaymentOrderRepository.class),
                mock(PaymentOrderService.class), mock(UserWalletRepository.class));
        Pageable firstPage = PageRequest.of(0, 20);
        WalletLedger ledger = new WalletLedger();
        ledger.setId(188L);
        when(ledgerRepository.searchAdminBeforeId(eq(null), eq(200L), eq("PAYMENT_RECHARGE"), eq("IN"),
                isNull(), isNull(), eq(firstPage))).thenReturn(new SliceImpl<>(List.of(ledger), firstPage, false));

        Map<String, Object> page = controller.adminLedger(userSession(1L, "ADMIN"),
                null, 6, 20, "PAYMENT_RECHARGE", "IN", null, null, 200L);

        assertEquals(188L, page.get("nextCursor"));
        assertEquals(false, page.get("hasMore"));
        verify(ledgerRepository).searchAdminBeforeId(null, 200L, "PAYMENT_RECHARGE", "IN", null, null, firstPage);
    }

    private BillingController controller(BillingService billingService,
                                         WalletLedgerRepository ledgerRepository,
                                         GenerationUsageLogRepository usageLogRepository,
                                         PaymentOrderRepository paymentOrderRepository,
                                         PaymentOrderService paymentOrderService) {
        return controller(billingService, ledgerRepository, usageLogRepository, paymentOrderRepository,
                paymentOrderService, mock(UserWalletRepository.class));
    }

    private BillingController controller(BillingService billingService,
                                         WalletLedgerRepository ledgerRepository,
                                         GenerationUsageLogRepository usageLogRepository,
                                         PaymentOrderRepository paymentOrderRepository,
                                         PaymentOrderService paymentOrderService,
                                         UserWalletRepository walletRepository) {
        BillingDailySummaryService dailySummaryService = mock(BillingDailySummaryService.class);
        when(dailySummaryService.findCompletePeriodSummary(any(), any(), any())).thenReturn(Optional.empty());
        return controller(billingService, ledgerRepository, usageLogRepository, paymentOrderRepository,
                paymentOrderService, walletRepository, dailySummaryService);
    }

    private BillingController controller(BillingService billingService,
                                         WalletLedgerRepository ledgerRepository,
                                         GenerationUsageLogRepository usageLogRepository,
                                         PaymentOrderRepository paymentOrderRepository,
                                         PaymentOrderService paymentOrderService,
                                         UserWalletRepository walletRepository,
                                         BillingDailySummaryService dailySummaryService) {
        return new BillingController(billingService, ledgerRepository, usageLogRepository,
                paymentOrderRepository, paymentOrderService, walletRepository, dailySummaryService,
                new CurrentUserService());
    }

    private MockHttpSession userSession(long userId, String role) {
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(userId, "user" + userId, "User " + userId, role));
        return session;
    }

    private PaymentOrder paymentOrder(long userId) {
        PaymentOrder order = new PaymentOrder();
        order.setOrderNo("R202607100001");
        order.setUserId(userId);
        order.setPoints(100L);
        order.setAmountCents(19900L);
        order.setCurrency("CNY");
        order.setProvider("manual");
        order.setProviderOrderNo("wx-123");
        order.setStatus("PENDING");
        order.setIdempotencyKey("client-req-1");
        return order;
    }
}
