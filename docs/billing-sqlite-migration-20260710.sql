-- 账务与调用审计增量迁移脚本（SQLite）
-- 适用场景：线上已有数据库需要显式补齐账务框架列和索引。
-- 执行建议：
-- 1. 先完整备份主库文件。
-- 2. 低峰期执行。
-- 3. SQLite 的 ADD COLUMN 通常不支持 IF NOT EXISTS；执行每个 ALTER 前先用 PRAGMA table_info(...) 确认列不存在。
-- 4. 如果应用已通过 spring.jpa.hibernate.ddl-auto=update 自动加过列，只执行缺失索引即可。

PRAGMA table_info(generation_usage_log);
PRAGMA table_info(generation_provider_task_ref);
PRAGMA table_info(billing_scheduler_state);
PRAGMA table_info(billing_reconciliation_anomaly_action);
PRAGMA table_info(billing_export_audit_log);

-- 供应商真实消耗回写字段。缺哪个列就执行哪一条。
ALTER TABLE generation_usage_log ADD COLUMN providerTaskId varchar(128);
ALTER TABLE generation_usage_log ADD COLUMN providerRawCost decimal(18,6);
ALTER TABLE generation_usage_log ADD COLUMN providerRawUnit varchar(32);
ALTER TABLE generation_usage_log ADD COLUMN costSource varchar(32);
ALTER TABLE generation_usage_log ADD COLUMN exchangeRate decimal(18,6);

-- 供应商任务号规范化引用表。用于按 provider + providerTaskId 精确反查本地调用审计。
CREATE TABLE IF NOT EXISTS generation_provider_task_ref (
    id integer primary key autoincrement,
    usageLogId bigint not null,
    userId bigint not null,
    provider varchar(64) not null,
    providerTaskId varchar(128) not null,
    costSource varchar(32),
    createdAt timestamp not null
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_task_ref_provider_task
    ON generation_provider_task_ref (provider, providerTaskId);
CREATE INDEX IF NOT EXISTS idx_provider_task_ref_provider_task_id
    ON generation_provider_task_ref (provider, providerTaskId, id);
CREATE INDEX IF NOT EXISTS idx_provider_task_ref_usage_id
    ON generation_provider_task_ref (usageLogId, id);
CREATE INDEX IF NOT EXISTS idx_provider_task_ref_user_id
    ON generation_provider_task_ref (userId, id);

-- 调度游标状态表。用于 USER_BATCH 定时巡检跨调度保存 lastUserId。
CREATE TABLE IF NOT EXISTS billing_scheduler_state (
    id integer primary key autoincrement,
    stateKey varchar(96) not null,
    lastUserId bigint,
    leaseOwner varchar(96),
    leaseUntil timestamp,
    version bigint,
    createdAt timestamp not null,
    updatedAt timestamp not null
);
-- 如果表已存在但缺少租约字段，先用 PRAGMA table_info(billing_scheduler_state) 确认后再按需执行。
ALTER TABLE billing_scheduler_state ADD COLUMN leaseOwner varchar(96);
ALTER TABLE billing_scheduler_state ADD COLUMN leaseUntil timestamp;
ALTER TABLE billing_scheduler_state ADD COLUMN version bigint;
CREATE UNIQUE INDEX IF NOT EXISTS uk_billing_scheduler_state_key
    ON billing_scheduler_state (stateKey);
CREATE INDEX IF NOT EXISTS idx_billing_scheduler_state_key
    ON billing_scheduler_state (stateKey);
CREATE INDEX IF NOT EXISTS idx_billing_scheduler_state_lease_until
    ON billing_scheduler_state (leaseUntil);
CREATE INDEX IF NOT EXISTS idx_billing_scheduler_state_updated
    ON billing_scheduler_state (updatedAt);

-- 对账异常人工处理记录。用于按 type + sourceId 形成稳定异常指纹，记录人工确认、忽略、已处理等状态。
CREATE TABLE IF NOT EXISTS billing_reconciliation_anomaly_action (
    id integer primary key autoincrement,
    anomalyKey varchar(160) not null,
    type varchar(64) not null,
    sourceId bigint not null,
    userId bigint,
    referenceNo varchar(160),
    status varchar(32) not null,
    note text,
    operatorUserId bigint not null,
    createdAt timestamp not null,
    updatedAt timestamp not null
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_billing_reconciliation_anomaly_action_key
    ON billing_reconciliation_anomaly_action (anomalyKey);
CREATE INDEX IF NOT EXISTS idx_billing_reconciliation_anomaly_action_key
    ON billing_reconciliation_anomaly_action (anomalyKey);
CREATE INDEX IF NOT EXISTS idx_billing_reconciliation_anomaly_action_type_status_id
    ON billing_reconciliation_anomaly_action (type, status, id);
CREATE INDEX IF NOT EXISTS idx_billing_reconciliation_anomaly_action_user_type_status_id
    ON billing_reconciliation_anomaly_action (userId, type, status, id);
CREATE INDEX IF NOT EXISTS idx_billing_reconciliation_anomaly_action_updated_id
    ON billing_reconciliation_anomaly_action (updatedAt, id);

-- CSV 导出审计日志。用于追踪谁导出了哪些账务数据、筛选条件、行数和是否达到导出上限。
CREATE TABLE IF NOT EXISTS billing_export_audit_log (
    id integer primary key autoincrement,
    exportId varchar(64) not null,
    operatorUserId bigint not null,
    scopeUserId bigint,
    exportType varchar(64) not null,
    filtersJson text,
    rowCount bigint not null,
    truncated boolean not null,
    status varchar(16) not null,
    errorMessage text,
    startedAt timestamp not null,
    finishedAt timestamp,
    durationMillis bigint not null
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_billing_export_audit_log_export_id
    ON billing_export_audit_log (exportId);
CREATE INDEX IF NOT EXISTS idx_billing_export_audit_log_export_id
    ON billing_export_audit_log (exportId);
CREATE INDEX IF NOT EXISTS idx_billing_export_audit_log_operator_id
    ON billing_export_audit_log (operatorUserId, id);
CREATE INDEX IF NOT EXISTS idx_billing_export_audit_log_type_status_id
    ON billing_export_audit_log (exportType, status, id);
CREATE INDEX IF NOT EXISTS idx_billing_export_audit_log_started_id
    ON billing_export_audit_log (startedAt, id);

-- 调用审计大表索引。
CREATE INDEX IF NOT EXISTS idx_usage_user_id
    ON generation_usage_log (userId, id);
CREATE INDEX IF NOT EXISTS idx_usage_user_started_id
    ON generation_usage_log (userId, startedAt, id);
CREATE INDEX IF NOT EXISTS idx_usage_user_status_provider_mode_id
    ON generation_usage_log (userId, status, provider, mode, id);
CREATE INDEX IF NOT EXISTS idx_usage_status_provider_mode_id
    ON generation_usage_log (status, provider, mode, id);
CREATE INDEX IF NOT EXISTS idx_usage_provider_task_id
    ON generation_usage_log (provider, providerTaskId, id);
CREATE INDEX IF NOT EXISTS idx_usage_cost_source_id
    ON generation_usage_log (costSource, id);

-- 钱包流水大表索引。
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_user_id
    ON wallet_ledger (userId, id);
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_user_created_id
    ON wallet_ledger (userId, createdAt, id);
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_user_type_direction_id
    ON wallet_ledger (userId, type, direction, id);
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_type_direction_id
    ON wallet_ledger (type, direction, id);
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_type_status_id
    ON wallet_ledger (type, status, id);

-- 支付订单大表索引。
CREATE INDEX IF NOT EXISTS idx_payment_order_user_id
    ON payment_order (userId, id);
CREATE INDEX IF NOT EXISTS idx_payment_order_user_created_id
    ON payment_order (userId, createdAt, id);
CREATE INDEX IF NOT EXISTS idx_payment_order_user_status_provider_id
    ON payment_order (userId, status, provider, id);
CREATE INDEX IF NOT EXISTS idx_payment_order_status_provider_id
    ON payment_order (status, provider, id);
CREATE INDEX IF NOT EXISTS idx_payment_order_status_user_id
    ON payment_order (status, userId, id);

-- 日汇总快照和对账巡检记录索引。
CREATE INDEX IF NOT EXISTS idx_billing_daily_summary_scope_date
    ON billing_daily_summary (scopeUserId, summaryDate);
CREATE INDEX IF NOT EXISTS idx_billing_daily_summary_date_scope
    ON billing_daily_summary (summaryDate, scopeUserId);
CREATE INDEX IF NOT EXISTS idx_billing_reconciliation_run_scope_id
    ON billing_reconciliation_run (scopeUserId, id);
CREATE INDEX IF NOT EXISTS idx_billing_reconciliation_run_status_id
    ON billing_reconciliation_run (status, id);
CREATE INDEX IF NOT EXISTS idx_billing_reconciliation_run_trigger_id
    ON billing_reconciliation_run (triggerType, id);
CREATE INDEX IF NOT EXISTS idx_billing_reconciliation_run_started_id
    ON billing_reconciliation_run (startedAt, id);
