package com.elebusiness.service.billing;

import com.elebusiness.model.entity.GenerationProviderTaskRef;
import com.elebusiness.model.entity.GenerationUsageLog;
import com.elebusiness.model.entity.UserWallet;
import com.elebusiness.model.entity.WalletLedger;
import com.elebusiness.repository.GenerationProviderTaskRefRepository;
import com.elebusiness.repository.GenerationUsageLogRepository;
import com.elebusiness.repository.UserWalletRepository;
import com.elebusiness.repository.WalletLedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 积分与调用审计服务。
 *
 * 第一阶段只记录框架数据，不真实扣减余额。
 */
@Service
public class BillingService {

    private final UserWalletRepository walletRepository;
    private final WalletLedgerRepository ledgerRepository;
    private final GenerationUsageLogRepository usageLogRepository;
    private final GenerationProviderTaskRefRepository providerTaskRefRepository;

    public BillingService(UserWalletRepository walletRepository,
                          WalletLedgerRepository ledgerRepository,
                          GenerationUsageLogRepository usageLogRepository,
                          GenerationProviderTaskRefRepository providerTaskRefRepository) {
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
        this.usageLogRepository = usageLogRepository;
        this.providerTaskRefRepository = providerTaskRefRepository;
    }

    @Transactional
    public WalletLedger creditPoints(long userId, long points, String type, String remark, String idempotencyKey) {
        if (points <= 0) {
            throw new IllegalArgumentException("入账积分必须大于 0");
        }
        Optional<WalletLedger> existing = findExistingLedger(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        UserWallet wallet = ensureWallet(userId);
        long balanceBefore = wallet.getBalancePoints();
        long frozenBefore = wallet.getFrozenPoints();
        wallet.setBalancePoints(balanceBefore + points);
        walletRepository.save(wallet);

        return writeLedger(
                userId,
                null,
                blankTo(type, "ADMIN_RECHARGE"),
                "IN",
                points,
                balanceBefore,
                wallet.getBalancePoints(),
                frozenBefore,
                wallet.getFrozenPoints(),
                idempotencyKey,
                remark
        );
    }

    @Transactional
    public GenerationUsageLog recordGenerationStarted(long userId, String taskId, String mode,
                                                      String agentId, int estimatedPoints) {
        int safeEstimated = Math.max(0, estimatedPoints);
        UserWallet wallet = ensureWallet(userId);
        if (safeEstimated > 0 && availablePoints(wallet) < safeEstimated) {
            throw new InsufficientBalanceException("可用积分不足，无法冻结本次生成预估积分");
        }

        GenerationUsageLog log = new GenerationUsageLog();
        log.setUserId(userId);
        log.setTaskId(blankTo(taskId, ""));
        log.setMode(blankTo(mode, "unknown"));
        log.setAgentId(blankTo(agentId, "unknown"));
        log.setProvider(resolveProvider(agentId));
        log.setEstimatedPoints(safeEstimated);
        log.setActualPoints(0);
        log.setStatus("STARTED");
        log.setStartedAt(LocalDateTime.now());
        GenerationUsageLog saved = usageLogRepository.save(log);
        if (safeEstimated > 0) {
            freezeForGeneration(wallet, saved, safeEstimated);
        }
        return saved;
    }

    @Transactional
    public void markGenerationSucceeded(Long usageLogId, int actualPoints) {
        markGenerationSucceeded(usageLogId, ProviderCost.actualPoints(actualPoints));
    }

    @Transactional
    public void markGenerationSucceeded(Long usageLogId, ProviderCost providerCost) {
        if (usageLogId == null) return;
        usageLogRepository.findById(usageLogId).ifPresent(log -> {
            if (isTerminal(log.getStatus())) return;
            ProviderCost safeCost = providerCost == null ? ProviderCost.actualPoints(0) : providerCost;
            int safeActual = Math.max(0, safeCost.actualPoints());
            long chargePoints = resolveChargePoints(log, safeActual);
            if (chargePoints > 0 || log.getEstimatedPoints() > 0) {
                settleGenerationCharge(log, chargePoints);
            }
            log.setStatus("SUCCEEDED");
            log.setActualPoints(toIntPoints(chargePoints));
            applyProviderCost(log, safeCost, safeActual);
            writeProviderTaskRefs(log, safeCost);
            log.setFinishedAt(LocalDateTime.now());
            usageLogRepository.save(log);
        });
    }

    @Transactional
    public void markGenerationFailed(Long usageLogId, String errorMessage) {
        if (usageLogId == null) return;
        usageLogRepository.findById(usageLogId).ifPresent(log -> {
            if (isTerminal(log.getStatus())) return;
            releaseGenerationFreeze(log, "GENERATION_RELEASE", "RELEASE", "生成失败释放冻结积分");
            log.setStatus("FAILED");
            log.setErrorMessage(errorMessage == null ? "" : errorMessage);
            log.setFinishedAt(LocalDateTime.now());
            usageLogRepository.save(log);
        });
    }

    @Transactional
    public void markGenerationCancelled(Long usageLogId) {
        if (usageLogId == null) return;
        usageLogRepository.findById(usageLogId).ifPresent(log -> {
            if (isTerminal(log.getStatus())) return;
            releaseGenerationFreeze(log, "GENERATION_RELEASE", "RELEASE", "生成取消释放冻结积分");
            log.setStatus("CANCELLED");
            log.setFinishedAt(LocalDateTime.now());
            usageLogRepository.save(log);
        });
    }

    @Transactional
    public UserWallet ensureWallet(long userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserWallet wallet = new UserWallet();
                    wallet.setUserId(userId);
                    wallet.setBalancePoints(0);
                    wallet.setFrozenPoints(0);
                    return walletRepository.save(wallet);
                });
    }

    private void freezeForGeneration(UserWallet wallet, GenerationUsageLog log, long points) {
        Optional<WalletLedger> existing = findExistingLedger("usage:" + log.getId() + ":freeze");
        if (existing.isPresent()) return;

        long balanceBefore = wallet.getBalancePoints();
        long frozenBefore = wallet.getFrozenPoints();
        wallet.setFrozenPoints(frozenBefore + points);
        walletRepository.save(wallet);

        writeLedger(
                wallet.getUserId(),
                log.getId(),
                "GENERATION_FREEZE",
                "FREEZE",
                points,
                balanceBefore,
                wallet.getBalancePoints(),
                frozenBefore,
                wallet.getFrozenPoints(),
                "usage:" + log.getId() + ":freeze",
                "生成开始冻结预估积分"
        );
    }

    private void settleGenerationCharge(GenerationUsageLog log, long actualPoints) {
        Optional<WalletLedger> existing = findExistingLedger("usage:" + log.getId() + ":charge");
        if (existing.isPresent()) return;

        UserWallet wallet = ensureWallet(log.getUserId());
        long balanceBefore = wallet.getBalancePoints();
        long frozenBefore = wallet.getFrozenPoints();
        long estimated = Math.max(0, log.getEstimatedPoints());
        long releaseFrozen = Math.min(estimated, frozenBefore);
        long frozenByOtherTasks = Math.max(0, frozenBefore - releaseFrozen);
        long spendableForThisUsage = Math.max(0, balanceBefore - frozenByOtherTasks);
        if (actualPoints > spendableForThisUsage) {
            throw new InsufficientBalanceException("钱包余额不足，无法结算本次生成实际积分");
        }
        wallet.setBalancePoints(balanceBefore - actualPoints);
        wallet.setFrozenPoints(Math.max(0, frozenBefore - releaseFrozen));
        walletRepository.save(wallet);

        writeLedger(
                wallet.getUserId(),
                log.getId(),
                "GENERATION_CHARGE",
                "OUT",
                -actualPoints,
                balanceBefore,
                wallet.getBalancePoints(),
                frozenBefore,
                wallet.getFrozenPoints(),
                "usage:" + log.getId() + ":charge",
                "生成成功扣减实际积分，释放预估冻结积分"
        );
    }

    private void releaseGenerationFreeze(GenerationUsageLog log, String type, String direction, String remark) {
        long estimated = Math.max(0, log.getEstimatedPoints());
        if (estimated <= 0) return;
        Optional<WalletLedger> existing = findExistingLedger("usage:" + log.getId() + ":release");
        if (existing.isPresent()) return;

        UserWallet wallet = ensureWallet(log.getUserId());
        long balanceBefore = wallet.getBalancePoints();
        long frozenBefore = wallet.getFrozenPoints();
        long release = Math.min(estimated, frozenBefore);
        if (release <= 0) return;
        wallet.setFrozenPoints(frozenBefore - release);
        walletRepository.save(wallet);

        writeLedger(
                wallet.getUserId(),
                log.getId(),
                type,
                direction,
                release,
                balanceBefore,
                wallet.getBalancePoints(),
                frozenBefore,
                wallet.getFrozenPoints(),
                "usage:" + log.getId() + ":release",
                remark
        );
    }

    private WalletLedger writeLedger(long userId, Long usageLogId, String type, String direction,
                                     long pointsDelta, long balanceBefore, long balanceAfter,
                                     long frozenBefore, long frozenAfter,
                                     String idempotencyKey, String remark) {
        Optional<WalletLedger> existing = findExistingLedger(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        WalletLedger ledger = new WalletLedger();
        ledger.setUserId(userId);
        ledger.setUsageLogId(usageLogId);
        ledger.setType(type);
        ledger.setDirection(direction);
        ledger.setPointsDelta(pointsDelta);
        ledger.setBalanceBefore(balanceBefore);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setFrozenBefore(frozenBefore);
        ledger.setFrozenAfter(frozenAfter);
        ledger.setStatus("POSTED");
        ledger.setIdempotencyKey(idempotencyKey);
        ledger.setRemark(remark == null ? "" : remark);
        return ledgerRepository.save(ledger);
    }

    private long resolveChargePoints(GenerationUsageLog log, int actualPoints) {
        if (actualPoints > 0) {
            return actualPoints;
        }
        return Math.max(0, log.getEstimatedPoints());
    }

    private void applyProviderCost(GenerationUsageLog log, ProviderCost providerCost, int safeActual) {
        log.setProviderTaskId(trimToNull(providerCost.providerTaskId(), 128));
        log.setProviderRawCost(nonNegativeScale(providerCost.providerRawCost()));
        log.setProviderRawUnit(trimToNull(providerCost.providerRawUnit(), 32));
        log.setExchangeRate(nonNegativeScale(providerCost.exchangeRate()));
        String source = trimToNull(providerCost.costSource(), 32);
        if (source == null) {
            source = safeActual > 0 ? "EXPLICIT_POINTS" : "ESTIMATED_FALLBACK";
        }
        log.setCostSource(source);
    }

    private void writeProviderTaskRefs(GenerationUsageLog log, ProviderCost providerCost) {
        if (providerTaskRefRepository == null || providerCost == null) {
            return;
        }
        String provider = trimToNull(log.getProvider(), 64);
        if (provider == null) {
            provider = "unknown";
        }
        for (String providerTaskId : splitProviderTaskIds(providerCost.providerTaskId())) {
            if (providerTaskRefRepository.findByProviderAndProviderTaskId(provider, providerTaskId).isPresent()) {
                continue;
            }
            GenerationProviderTaskRef ref = new GenerationProviderTaskRef();
            ref.setUsageLogId(log.getId());
            ref.setUserId(log.getUserId());
            ref.setProvider(provider);
            ref.setProviderTaskId(providerTaskId);
            ref.setCostSource(log.getCostSource());
            ref.setCreatedAt(LocalDateTime.now());
            providerTaskRefRepository.save(ref);
        }
    }

    private Set<String> splitProviderTaskIds(String value) {
        Set<String> ids = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return ids;
        }
        for (String part : value.split(",")) {
            String id = trimToNull(part, 128);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private BigDecimal nonNegativeScale(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal safe = value.signum() < 0 ? BigDecimal.ZERO : value;
        return safe.setScale(6, RoundingMode.HALF_UP);
    }

    private int toIntPoints(long points) {
        if (points <= 0) {
            return 0;
        }
        return points > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) points;
    }

    private Optional<WalletLedger> findExistingLedger(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        Optional<WalletLedger> existing = ledgerRepository.findByIdempotencyKey(idempotencyKey);
        return existing == null ? Optional.empty() : existing;
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status);
    }

    private long availablePoints(UserWallet wallet) {
        return wallet.getBalancePoints() - wallet.getFrozenPoints();
    }

    private String resolveProvider(String agentId) {
        if (agentId == null || agentId.isBlank()) return "unknown";
        String lower = agentId.toLowerCase();
        if (lower.contains("liblib")) return "liblib";
        if (lower.contains("gpt")) return "openai-compatible";
        if (lower.contains("gemini")) return "gemini";
        if (lower.contains("qwen")) return "dashscope";
        if (lower.contains("wan")) return "dashscope";
        if (lower.contains("hunyuan")) return "tencent";
        return lower;
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String trimToNull(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    public record ProviderCost(
            int actualPoints,
            String providerTaskId,
            BigDecimal providerRawCost,
            String providerRawUnit,
            String costSource,
            BigDecimal exchangeRate
    ) {
        public static ProviderCost actualPoints(int actualPoints) {
            return new ProviderCost(actualPoints, null, null, null, null, null);
        }
    }

    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(String message) {
            super(message);
        }
    }
}
