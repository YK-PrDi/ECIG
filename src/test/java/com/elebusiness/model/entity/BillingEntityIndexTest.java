package com.elebusiness.model.entity;

import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingEntityIndexTest {

    @Test
    void walletLedgerHasCompositeIndexesForCursorFilteringAndReconciliation() {
        Map<String, String> indexes = indexes(WalletLedger.class);

        assertEquals("userId, id", indexes.get("idx_wallet_ledger_user_id"));
        assertEquals("userId, createdAt, id", indexes.get("idx_wallet_ledger_user_created_id"));
        assertEquals("userId, type, direction, id", indexes.get("idx_wallet_ledger_user_type_direction_id"));
        assertEquals("status, type, userId", indexes.get("idx_wallet_ledger_status_type_user"));
        assertEquals("type, direction, id", indexes.get("idx_wallet_ledger_type_direction_id"));
        assertEquals("type, status, id", indexes.get("idx_wallet_ledger_type_status_id"));
    }

    @Test
    void generationUsageHasCompositeIndexesForCursorFilteringAndOpenUsageChecks() {
        Map<String, String> indexes = indexes(GenerationUsageLog.class);

        assertEquals("userId, id", indexes.get("idx_usage_user_id"));
        assertEquals("userId, startedAt, id", indexes.get("idx_usage_user_started_id"));
        assertEquals("userId, status, provider, mode, id", indexes.get("idx_usage_user_status_provider_mode_id"));
        assertEquals("status, userId", indexes.get("idx_usage_status_user"));
        assertEquals("status, provider, mode, id", indexes.get("idx_usage_status_provider_mode_id"));
        assertEquals("provider, providerTaskId, id", indexes.get("idx_usage_provider_task_id"));
        assertEquals("costSource, id", indexes.get("idx_usage_cost_source_id"));
    }

    @Test
    void paymentOrderHasCompositeIndexesForCursorFilteringAndPaidOrderChecks() {
        Map<String, String> indexes = indexes(PaymentOrder.class);

        assertEquals("userId, id", indexes.get("idx_payment_order_user_id"));
        assertEquals("userId, createdAt, id", indexes.get("idx_payment_order_user_created_id"));
        assertEquals("userId, status, provider, id", indexes.get("idx_payment_order_user_status_provider_id"));
        assertEquals("status, userId", indexes.get("idx_payment_order_status_user"));
        assertEquals("status, provider, id", indexes.get("idx_payment_order_status_provider_id"));
        assertEquals("status, userId, id", indexes.get("idx_payment_order_status_user_id"));
    }

    @Test
    void billingDailySummaryHasScopedDateUniqueKeyAndQueryIndexes() {
        Table table = BillingDailySummary.class.getAnnotation(Table.class);
        Map<String, String> indexes = indexes(BillingDailySummary.class);

        assertEquals("scopeUserId, summaryDate", indexes.get("idx_billing_daily_summary_scope_date"));
        assertEquals("summaryDate, scopeUserId", indexes.get("idx_billing_daily_summary_date_scope"));
        assertTrue(Arrays.stream(table.uniqueConstraints())
                .map(UniqueConstraint::columnNames)
                .anyMatch(columns -> Arrays.equals(new String[]{"scopeUserId", "summaryDate"}, columns)));
    }

    @Test
    void billingReconciliationRunHasScopedCursorAndOperationalIndexes() {
        Table table = BillingReconciliationRun.class.getAnnotation(Table.class);
        Map<String, String> indexes = indexes(BillingReconciliationRun.class);

        assertEquals("scopeUserId, id", indexes.get("idx_billing_reconciliation_run_scope_id"));
        assertEquals("status, id", indexes.get("idx_billing_reconciliation_run_status_id"));
        assertEquals("triggerType, id", indexes.get("idx_billing_reconciliation_run_trigger_id"));
        assertEquals("startedAt, id", indexes.get("idx_billing_reconciliation_run_started_id"));
        assertTrue(Arrays.stream(table.uniqueConstraints())
                .map(UniqueConstraint::columnNames)
                .anyMatch(columns -> Arrays.equals(new String[]{"runId"}, columns)));
    }

    @Test
    void generationProviderTaskRefHasExactLookupAndUsageBackrefIndexes() {
        Table table = GenerationProviderTaskRef.class.getAnnotation(Table.class);
        Map<String, String> indexes = indexes(GenerationProviderTaskRef.class);

        assertEquals("provider, providerTaskId, id", indexes.get("idx_provider_task_ref_provider_task_id"));
        assertEquals("usageLogId, id", indexes.get("idx_provider_task_ref_usage_id"));
        assertEquals("userId, id", indexes.get("idx_provider_task_ref_user_id"));
        assertTrue(Arrays.stream(table.uniqueConstraints())
                .map(UniqueConstraint::columnNames)
                .anyMatch(columns -> Arrays.equals(new String[]{"provider", "providerTaskId"}, columns)));
    }

    @Test
    void billingSchedulerStateHasUniqueStateKeyAndOperationalIndexes() {
        Table table = BillingSchedulerState.class.getAnnotation(Table.class);
        Map<String, String> indexes = indexes(BillingSchedulerState.class);

        assertEquals("stateKey", indexes.get("idx_billing_scheduler_state_key"));
        assertEquals("leaseUntil", indexes.get("idx_billing_scheduler_state_lease_until"));
        assertEquals("updatedAt", indexes.get("idx_billing_scheduler_state_updated"));
        assertTrue(Arrays.stream(table.uniqueConstraints())
                .map(UniqueConstraint::columnNames)
                .anyMatch(columns -> Arrays.equals(new String[]{"stateKey"}, columns)));
    }

    @Test
    void billingReconciliationAnomalyActionHasStableKeyAndOperationalIndexes() {
        Table table = BillingReconciliationAnomalyAction.class.getAnnotation(Table.class);
        Map<String, String> indexes = indexes(BillingReconciliationAnomalyAction.class);

        assertEquals("anomalyKey", indexes.get("idx_billing_reconciliation_anomaly_action_key"));
        assertEquals("type, status, id", indexes.get("idx_billing_reconciliation_anomaly_action_type_status_id"));
        assertEquals("userId, type, status, id", indexes.get("idx_billing_reconciliation_anomaly_action_user_type_status_id"));
        assertEquals("updatedAt, id", indexes.get("idx_billing_reconciliation_anomaly_action_updated_id"));
        assertTrue(Arrays.stream(table.uniqueConstraints())
                .map(UniqueConstraint::columnNames)
                .anyMatch(columns -> Arrays.equals(new String[]{"anomalyKey"}, columns)));
    }

    @Test
    void billingExportAuditLogHasOperationalIndexes() {
        Table table = BillingExportAuditLog.class.getAnnotation(Table.class);
        Map<String, String> indexes = indexes(BillingExportAuditLog.class);

        assertEquals("exportId", indexes.get("idx_billing_export_audit_log_export_id"));
        assertEquals("operatorUserId, id", indexes.get("idx_billing_export_audit_log_operator_id"));
        assertEquals("exportType, status, id", indexes.get("idx_billing_export_audit_log_type_status_id"));
        assertEquals("startedAt, id", indexes.get("idx_billing_export_audit_log_started_id"));
        assertTrue(Arrays.stream(table.uniqueConstraints())
                .map(UniqueConstraint::columnNames)
                .anyMatch(columns -> Arrays.equals(new String[]{"exportId"}, columns)));
    }

    private Map<String, String> indexes(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        return Arrays.stream(table.indexes())
                .collect(Collectors.toMap(Index::name, Index::columnList));
    }
}
