package com.elebusiness.service.billing;

import com.elebusiness.model.entity.PaymentOrder;
import com.elebusiness.repository.PaymentOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentOrderService {

    private final PaymentOrderRepository orderRepository;
    private final BillingService billingService;

    public PaymentOrderService(PaymentOrderRepository orderRepository, BillingService billingService) {
        this.orderRepository = orderRepository;
        this.billingService = billingService;
    }

    @Transactional
    public PaymentOrder createRechargeOrder(long userId, long points, long amountCents,
                                            String provider, String idempotencyKey) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId 必须大于 0");
        }
        if (points <= 0) {
            throw new IllegalArgumentException("充值积分必须大于 0");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("支付金额必须大于 0");
        }

        String safeIdempotencyKey = normalizeIdempotencyKey(userId, idempotencyKey);
        Optional<PaymentOrder> existing = orderRepository.findByIdempotencyKey(safeIdempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        LocalDateTime now = LocalDateTime.now();
        PaymentOrder order = new PaymentOrder();
        order.setOrderNo(newOrderNo());
        order.setUserId(userId);
        order.setPoints(points);
        order.setAmountCents(amountCents);
        order.setCurrency("CNY");
        order.setProvider(normalizeProvider(provider));
        order.setStatus("PENDING");
        order.setIdempotencyKey(safeIdempotencyKey);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return orderRepository.save(order);
    }

    @Transactional
    public PaymentOrder markPaid(String orderNo, String providerOrderNo, long paidAmountCents) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo 不能为空");
        }
        PaymentOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("支付订单不存在: " + orderNo));

        if ("PAID".equalsIgnoreCase(order.getStatus())) {
            return order;
        }
        if (!"PENDING".equalsIgnoreCase(order.getStatus())) {
            throw new IllegalStateException("只有待支付订单可以确认支付");
        }
        if (paidAmountCents != order.getAmountCents()) {
            throw new IllegalArgumentException("支付金额与订单金额不一致");
        }

        billingService.creditPoints(
                order.getUserId(),
                order.getPoints(),
                "PAYMENT_RECHARGE",
                "支付订单 " + order.getOrderNo() + " 入账",
                "payment:" + order.getOrderNo()
        );

        LocalDateTime now = LocalDateTime.now();
        order.setStatus("PAID");
        order.setProviderOrderNo(providerOrderNo == null ? "" : providerOrderNo.trim());
        order.setPaidAt(now);
        order.setUpdatedAt(now);
        return orderRepository.save(order);
    }

    private String normalizeIdempotencyKey(long userId, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyKey.trim();
        }
        return "recharge:" + userId + ":" + UUID.randomUUID();
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "manual";
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > 32 ? normalized.substring(0, 32) : normalized;
    }

    private String newOrderNo() {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        return "R" + time + suffix;
    }
}
