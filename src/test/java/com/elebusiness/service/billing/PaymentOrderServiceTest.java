package com.elebusiness.service.billing;

import com.elebusiness.model.entity.PaymentOrder;
import com.elebusiness.model.entity.WalletLedger;
import com.elebusiness.repository.PaymentOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOrderServiceTest {

    @Mock
    PaymentOrderRepository orderRepository;

    @Mock
    BillingService billingService;

    @InjectMocks
    PaymentOrderService paymentOrderService;

    @Test
    void createRechargeOrderWritesPendingOrderWithIdempotencyKey() {
        when(orderRepository.findByIdempotencyKey("client-req-1")).thenReturn(Optional.empty());
        when(orderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentOrder order = paymentOrderService.createRechargeOrder(
                1001L, 100, 19900, "manual", "client-req-1");

        assertEquals(1001L, order.getUserId());
        assertEquals(100L, order.getPoints());
        assertEquals(19900L, order.getAmountCents());
        assertEquals("CNY", order.getCurrency());
        assertEquals("manual", order.getProvider());
        assertEquals("PENDING", order.getStatus());
        assertEquals("client-req-1", order.getIdempotencyKey());
        assertNotNull(order.getOrderNo());
        assertNotNull(order.getCreatedAt());
    }

    @Test
    void createRechargeOrderReturnsExistingOrderForSameIdempotencyKey() {
        PaymentOrder existing = pendingOrder();
        existing.setIdempotencyKey("client-req-1");
        when(orderRepository.findByIdempotencyKey("client-req-1")).thenReturn(Optional.of(existing));

        PaymentOrder order = paymentOrderService.createRechargeOrder(
                1001L, 100, 19900, "manual", "client-req-1");

        assertEquals(existing, order);
        verify(orderRepository, never()).save(any(PaymentOrder.class));
    }

    @Test
    void markPaidCreditsWalletOnceAndMarksOrderPaid() {
        PaymentOrder order = pendingOrder();
        when(orderRepository.findByOrderNo("R202607100001")).thenReturn(Optional.of(order));
        when(orderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        WalletLedger ledger = new WalletLedger();
        ledger.setUserId(1001L);
        ledger.setPointsDelta(100L);
        when(billingService.creditPoints(
                1001L, 100L, "PAYMENT_RECHARGE", "支付订单 R202607100001 入账", "payment:R202607100001"
        )).thenReturn(ledger);

        PaymentOrder paid = paymentOrderService.markPaid("R202607100001", "wx-123", 19900);

        assertEquals("PAID", paid.getStatus());
        assertEquals("wx-123", paid.getProviderOrderNo());
        assertNotNull(paid.getPaidAt());
        verify(billingService).creditPoints(
                1001L, 100L, "PAYMENT_RECHARGE", "支付订单 R202607100001 入账", "payment:R202607100001");

        ArgumentCaptor<PaymentOrder> captor = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(orderRepository).save(captor.capture());
        assertEquals("PAID", captor.getValue().getStatus());
    }

    @Test
    void markPaidIsIdempotentForAlreadyPaidOrder() {
        PaymentOrder order = pendingOrder();
        order.setStatus("PAID");
        when(orderRepository.findByOrderNo("R202607100001")).thenReturn(Optional.of(order));

        PaymentOrder paid = paymentOrderService.markPaid("R202607100001", "wx-123", 19900);

        assertEquals(order, paid);
        verify(billingService, never()).creditPoints(any(Long.class), any(Long.class), any(), any(), any());
        verify(orderRepository, never()).save(any(PaymentOrder.class));
    }

    @Test
    void markPaidRejectsMismatchedAmount() {
        PaymentOrder order = pendingOrder();
        when(orderRepository.findByOrderNo("R202607100001")).thenReturn(Optional.of(order));

        assertThrows(IllegalArgumentException.class,
                () -> paymentOrderService.markPaid("R202607100001", "wx-123", 19800));

        verify(billingService, never()).creditPoints(any(Long.class), any(Long.class), any(), any(), any());
        verify(orderRepository, never()).save(any(PaymentOrder.class));
    }

    private PaymentOrder pendingOrder() {
        PaymentOrder order = new PaymentOrder();
        order.setOrderNo("R202607100001");
        order.setUserId(1001L);
        order.setPoints(100L);
        order.setAmountCents(19900L);
        order.setCurrency("CNY");
        order.setProvider("manual");
        order.setStatus("PENDING");
        return order;
    }
}
