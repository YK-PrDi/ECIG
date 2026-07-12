package com.elebusiness.service.billing;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.BillingSchedulerState;
import com.elebusiness.repository.BillingSchedulerStateRepository;
import com.elebusiness.repository.UserWalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class BillingReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingReconciliationScheduler.class);
    private static final String USER_BATCH_STATE_KEY = "BILLING_RECONCILIATION_USER_BATCH";

    private final BillingReconciliationRunService runService;
    private final UserWalletRepository walletRepository;
    private final BillingSchedulerStateRepository stateRepository;
    private final AppProperties properties;
    private final String instanceId = UUID.randomUUID().toString();

    @Autowired
    public BillingReconciliationScheduler(BillingReconciliationRunService runService,
                                          UserWalletRepository walletRepository,
                                          BillingSchedulerStateRepository stateRepository,
                                          AppProperties properties) {
        this.runService = runService;
        this.walletRepository = walletRepository;
        this.stateRepository = stateRepository;
        this.properties = properties;
    }

    public BillingReconciliationScheduler(BillingReconciliationRunService runService,
                                          UserWalletRepository walletRepository,
                                          AppProperties properties) {
        this(runService, walletRepository, null, properties);
    }

    public BillingReconciliationScheduler(BillingReconciliationRunService runService,
                                          AppProperties properties) {
        this(runService, null, null, properties);
    }

    @Scheduled(cron = "${app.billing.reconciliation-cron:0 0 3 * * *}")
    public void runScheduledReconciliation() {
        AppProperties.Billing billing = properties.getBilling();
        if (billing == null || !billing.isReconciliationScheduleEnabled()) {
            return;
        }
        if ("USER_BATCH".equalsIgnoreCase(billing.getReconciliationScheduleMode())) {
            runScheduledUserBatchReconciliation(billing);
            return;
        }
        runScheduledGlobalReconciliation();
    }

    private void runScheduledGlobalReconciliation() {
        try {
            BillingReconciliationRunService.RunResult result = runService.runAndRecord(null, "SCHEDULED", null);
            BillingReconciliationService.Report report = result == null ? null : result.report();
            log.info("账务定时对账完成: healthy={}, checks={}",
                    report != null && report.healthy(),
                    report == null || report.checks() == null ? 0 : report.checks().size());
        } catch (RuntimeException e) {
            log.warn("账务定时对账失败，失败记录已由 runService 尝试落库: {}", e.getMessage());
            log.debug("账务定时对账异常详情", e);
        }
    }

    private void runScheduledUserBatchReconciliation(AppProperties.Billing billing) {
        if (walletRepository == null) {
            log.warn("账务分批定时对账跳过: UserWalletRepository 未注入");
            return;
        }
        int batchSize = normalizeBatchSize(billing.getReconciliationUserBatchSize());
        PageRequest pageRequest = PageRequest.of(0, batchSize);
        BillingSchedulerState state = loadUserBatchState();
        if (!acquireUserBatchLease(state, billing)) {
            log.info("账务分批定时对账跳过: 现有租约仍有效");
            return;
        }
        Long afterUserId = state.getLastUserId();
        long processed = 0L;
        List<Long> userIds = walletRepository.findUserIdsAfter(afterUserId, pageRequest);
        if (userIds != null) {
            for (Long userId : userIds) {
                if (userId == null) {
                    continue;
                }
                afterUserId = userId;
                try {
                    runService.runAndRecord(userId, "SCHEDULED_USER_BATCH", null);
                    processed++;
                } catch (RuntimeException e) {
                    log.warn("账务分批定时对账失败: userId={}, error={}", userId, e.getMessage());
                    log.debug("账务分批定时对账异常详情: userId=" + userId, e);
                }
            }
        }
        if (userIds == null || userIds.size() < batchSize) {
            afterUserId = null;
        }
        saveUserBatchState(state, afterUserId);
        log.info("账务分批定时对账完成: processed={}, batchSize={}, mode={}",
                processed, batchSize, normalizeMode(billing.getReconciliationScheduleMode()));
    }

    private boolean acquireUserBatchLease(BillingSchedulerState state, AppProperties.Billing billing) {
        LocalDateTime now = LocalDateTime.now();
        if (state.getLeaseUntil() != null && state.getLeaseUntil().isAfter(now)) {
            return false;
        }
        state.setLeaseOwner(instanceId);
        state.setLeaseUntil(now.plusSeconds(normalizeLeaseSeconds(billing.getReconciliationLeaseSeconds())));
        if (stateRepository == null) {
            return true;
        }
        try {
            stateRepository.save(state);
            return true;
        } catch (RuntimeException e) {
            log.warn("账务分批定时对账租约获取失败: {}", e.getMessage());
            log.debug("账务分批定时对账租约获取异常详情", e);
            return false;
        }
    }

    private BillingSchedulerState loadUserBatchState() {
        if (stateRepository == null) {
            BillingSchedulerState state = new BillingSchedulerState();
            state.setStateKey(USER_BATCH_STATE_KEY);
            return state;
        }
        return stateRepository.findByStateKey(USER_BATCH_STATE_KEY).orElseGet(() -> {
            BillingSchedulerState state = new BillingSchedulerState();
            state.setStateKey(USER_BATCH_STATE_KEY);
            return state;
        });
    }

    private void saveUserBatchState(BillingSchedulerState state, Long lastUserId) {
        state.setStateKey(USER_BATCH_STATE_KEY);
        state.setLastUserId(lastUserId);
        state.setLeaseOwner(null);
        state.setLeaseUntil(null);
        if (stateRepository != null) {
            stateRepository.save(state);
        }
    }

    private int normalizeBatchSize(int batchSize) {
        return Math.max(1, Math.min(500, batchSize));
    }

    private int normalizeLeaseSeconds(int leaseSeconds) {
        return Math.max(30, Math.min(3600, leaseSeconds));
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "GLOBAL";
        }
        return mode.trim().toUpperCase(Locale.ROOT);
    }
}
