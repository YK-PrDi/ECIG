# 积分钱包、支付订单、流水和调用审计框架

## 当前边界

主库保存平台级账务数据，用户工作库只保存个人业务数据。账务不能拆到每个用户的 `workspace.db`，否则后续对账、退款、风控、账单导出和支付回调会变复杂。

当前代码已经具备这些主库能力：

- `user_wallet`：用户钱包，记录总余额和冻结积分。
- `wallet_ledger`：钱包流水，记录每次余额或冻结变化的前后快照，使用 `idempotencyKey` 防止重复入账或重复扣费。
- `generation_usage_log`：模型调用审计，记录用户、任务、模式、模型、供应商、预估积分、实际积分、供应商任务号、供应商原始消耗、换算来源、状态和错误。
- `generation_provider_task_ref`：供应商任务号规范化引用表，把一条调用审计里的多个供应商任务号拆成独立行，便于按 `provider + providerTaskId` 精确反查。
- `billing_scheduler_state`：后台调度状态表，保存分批巡检等后台任务的游标和租约，避免单次调度扫描所有用户，也避免多实例同时推进同一个游标。
- `billing_reconciliation_anomaly_action`：对账异常处理记录表，按稳定异常指纹记录人工确认、忽略、已处理等状态，避免同一异常在大表巡检中被反复人工核对。
- `billing_export_audit_log`：CSV 导出审计日志，记录导出操作者、作用域、导出类型、筛选条件、导出行数和是否达到导出上限。
- `payment_order`：充值订单，记录订单号、用户、积分、金额、支付渠道、渠道单号、状态和幂等键。
- `GenerationPricingService`：集中计算预估积分，避免生成入口散落硬编码。

默认 `app.billing.charge-enabled=false`，所以生成调用仍然不会因为余额不足被阻断。打开后，生成开始会冻结预估积分，成功后结算；如果还没有真实消耗值，则按预估积分结算。

## 资金流

1. 用户创建充值订单：写 `payment_order=PENDING`，不改钱包。
2. 支付平台回调或管理员确认支付：调用 `PaymentOrderService.markPaid`。
3. `markPaid` 校验订单状态和金额后，调用 `BillingService.creditPoints`。
4. `creditPoints` 增加 `user_wallet.balancePoints`，写 `wallet_ledger=PAYMENT_RECHARGE`。
5. 订单状态更新为 `PAID`，同一个订单再次确认支付不会重复入账。

不要让支付回调直接改 `user_wallet`。所有资金变化必须经过 `BillingService`，这样钱包、流水和订单状态才能保持一致。

## 生成扣费流

1. 生成开始：调用 `recordGenerationStarted`，写 `generation_usage_log=STARTED`。如果预估积分大于 0，检查可用积分并冻结。
2. 生成成功：调用 `markGenerationSucceeded`，状态变为 `SUCCEEDED`，扣减实际积分；如果实际积分为 0 但有预估积分，则按预估积分扣减。
3. 生成失败：调用 `markGenerationFailed`，状态变为 `FAILED`，释放本次冻结积分。
4. 生成取消：调用 `markGenerationCancelled`，状态变为 `CANCELLED`，释放本次冻结积分。

结算时只能使用“当前可用余额 + 本任务自己的冻结积分”，不会挪用其他任务已冻结的额度。终态日志不会重复扣费或重复释放。

## 当前接口

- `GET /api/billing/wallet`：当前用户钱包快照。
- `GET /api/billing/ledger?page=0&limit=50&type=GENERATION_CHARGE&direction=OUT&from=2026-07-10&to=2026-07-11`：当前用户流水列表，数据库分页，固定按当前登录用户隔离。
- `GET /api/billing/usage?page=0&limit=50&status=SUCCEEDED&provider=liblib&mode=custom&from=2026-07-10&to=2026-07-11`：当前用户调用审计列表，数据库分页。
- `GET /api/billing/payment-orders?page=0&limit=50&status=PAID&provider=manual&from=2026-07-10&to=2026-07-11`：当前用户充值订单列表，数据库分页。
- `GET /api/billing/ledger/export?...`：当前用户流水 CSV 导出，筛选参数同列表接口。
- `GET /api/billing/usage/export?...`：当前用户调用审计 CSV 导出。
- `GET /api/billing/payment-orders/export?...`：当前用户充值订单 CSV 导出。
- `POST /api/billing/payment-orders`：当前用户创建充值订单。
- `POST /api/billing/admin/payment-orders/{orderNo}/paid`：管理员模拟确认支付成功，用于后续真实支付回调复用。
- `POST /api/billing/payment-callbacks/{provider}`：支付平台回调占位入口，需要 `X-Billing-Callback-Token` 与 `app.billing.callback-token` 一致；默认 token 为空时入口不可用。
- `POST /api/billing/admin/credit`：管理员人工入账，用于补单、赠送、迁移或临时调整。
- `GET /api/billing/admin/wallet?userId=2002`：管理员查看指定用户钱包。
- `GET /api/billing/admin/summary?userId=2002&from=2026-07-10&to=2026-07-11`：管理员账务汇总；`userId` 可为空，空值表示全局汇总。
- `POST /api/billing/admin/daily-summary/refresh?date=2026-07-10&userId=2002`：管理员刷新某天、某用户的账务日汇总快照；`userId` 为空时刷新全局快照。
- `POST /api/billing/admin/daily-summary/refresh-range?from=2026-07-01&to=2026-07-10&userId=2002`：管理员按日期范围批量刷新日汇总快照，单次最多 31 天；响应会返回每一天的 `SUCCESS/FAILED` 状态，便于后台重试失败日期。
- `GET /api/billing/admin/reconciliation?userId=2002`：管理员账务一致性检查；`userId` 可为空，空值表示全局检查。
- `GET /api/billing/admin/reconciliation/anomalies?type=PAID_ORDER_LEDGER&userId=2002&limit=50&cursor=120`：管理员查看对账异常明细，支持 `PAID_ORDER_LEDGER`、`PAYMENT_LEDGER_ORDER`、`USAGE_CHARGE_LEDGER`、`CHARGE_LEDGER_USAGE` 四类，使用游标分页。
- `GET /api/billing/admin/reconciliation/anomalies/export?type=PAID_ORDER_LEDGER&userId=2002`：管理员导出对账异常明细 CSV，包含异常处理状态摘要，按游标分批读取。
- `GET /api/billing/admin/reconciliation/anomaly-actions?type=PAID_ORDER_LEDGER&userId=2002&status=IGNORED&cursor=120&limit=50`：管理员查看对账异常人工处理记录，按处理记录表游标分页。
- `PUT /api/billing/admin/reconciliation/anomaly-actions`：管理员按 `type + sourceId` 记录或更新某条异常的人工处理状态，支持 `OPEN`、`ACKNOWLEDGED`、`IGNORED`、`RESOLVED`。
- `POST /api/billing/admin/reconciliation/runs?userId=2002&triggerType=MANUAL`：管理员发起一次对账巡检并把运行结果写入 `billing_reconciliation_run`；`userId` 为空时保存全局巡检记录。
- `GET /api/billing/admin/reconciliation/runs?userId=2002&status=SUCCESS&triggerType=MANUAL&cursor=120&limit=50`：管理员查看历史对账巡检记录，支持按用户、状态、触发类型、时间范围筛选，并使用游标分页。
- `GET /api/billing/admin/reconciliation/runs/{runId}`：管理员查看单次对账巡检记录，包含运行状态、异常总量、耗时、错误信息和报告快照。
- `GET /api/billing/admin/ledger?userId=2002&page=0&limit=50&type=PAYMENT_RECHARGE&direction=IN`：管理员查看指定用户或全局流水，数据库分页。
- `GET /api/billing/admin/usage?userId=2002&page=0&limit=50&status=FAILED&provider=liblib`：管理员查看指定用户或全局调用审计，数据库分页。
- `GET /api/billing/admin/provider-task-ref?provider=liblib&providerTaskId=...`：管理员按供应商任务号精确反查本地调用审计，返回规范化引用行和对应 `generation_usage_log` 摘要。
- `GET /api/billing/admin/scheduler-states`：管理员查看后台调度游标和租约状态，用于排查分批巡检是否卡住、租约是否仍有效。
- `GET /api/billing/admin/export-audits?operatorUserId=1&exportType=USAGE&status=SUCCESS&cursor=120&limit=50`：管理员查看账务 CSV 导出审计日志，支持按操作者、导出类型、状态筛选，并使用游标分页。
- `GET /api/billing/admin/export-audits/export?operatorUserId=1&exportType=USAGE&status=SUCCESS`：管理员导出账务 CSV 导出审计日志，便于离线审查敏感数据导出行为。
- `GET /api/billing/admin/payment-orders?userId=2002&page=0&limit=50&status=PENDING&provider=manual`：管理员查看指定用户或全局充值订单，数据库分页。
- `GET /api/billing/admin/ledger/export?...`：管理员流水 CSV 导出，`userId` 可为空表示全局导出。
- `GET /api/billing/admin/usage/export?...`：管理员调用审计 CSV 导出。
- `GET /api/billing/admin/payment-orders/export?...`：管理员充值订单 CSV 导出。

分页响应包含：

```json
{
  "items": [],
  "page": 0,
  "limit": 50,
  "hasMore": false,
  "nextCursor": 12345
}
```

这几个大表接口必须使用仓库层 `Pageable/Slice` 查询，不能先全量查出后再在内存里截断。列表接口兼容 `page/limit`，也支持 `cursor`：

- 首次请求不传 `cursor` 且 `page=0` 时，后端按 `id < Long.MAX_VALUE` 做 keyset 首页查询，并返回 `nextCursor`。
- 后续请求传 `cursor=上次返回的 nextCursor`，后端按 `id < cursor` 做 keyset 查询。
- 带 `cursor` 时后端忽略 `page`，固定从游标之后继续取，避免大 offset 查询越来越慢。
- `page>0` 只保留给旧调用兼容；新前端和运营工具应使用 `nextCursor` 翻页。
- 前端账务管理页已经使用 `nextCursor` 实现“加载更多”。

CSV 导出由 `BillingCsvExportService` 按 `id desc` 做游标分批读取，首批使用 `id < Long.MAX_VALUE`，后续批次使用上一批最后一条记录的 `id`，每批 500 条，默认最多导出 `app.billing.export-max-rows=10000` 条。调用审计 CSV 已包含 `providerTaskId`、`providerRawCost`、`providerRawUnit`、`costSource`、`exchangeRate`，方便后续按供应商账单对账。对账异常 CSV 复用异常明细接口的 `nextCursor` 分批读取，每批最多 200 条，导出字段包含 `anomalyKey`、`actionStatus`、`actionNote` 和 `actionUpdatedAt`，方便运营离线核对。导出审计日志 CSV 由 `BillingExportAuditService` 复用同一个 `searchLogs` 仓库查询按游标分批读取，不走大 offset。

每次账务 CSV 导出成功后都会写入 `billing_export_audit_log`，保存 `operatorUserId`、`scopeUserId`、`exportType`、`filtersJson`、`rowCount`、`truncated` 和时间信息。导出服务抛出异常时也会尽量写入 `status=FAILED` 的失败审计，记录筛选条件和错误信息；如果失败审计本身写入失败，控制器会保留原始导出异常语义，不用审计异常掩盖真实失败。这个日志只做导出行为审计，不参与钱包、流水、订单、调用审计和对账口径计算。这样可以支持日常对账和排查，同时避免一次请求把超大表完整加载进内存，也避免大 offset 查询越来越慢。需要全量离线对账时，应在服务器侧单独做后台任务或数据库只读导出。

导出审计日志可通过 `GET /api/billing/admin/export-audits` 查询，按 `id desc` 和 `cursor` 翻页，单次最多 200 条。运营排查敏感数据导出时，优先用 `operatorUserId`、`exportType`、`status` 缩小范围，再根据 `filtersJson` 还原当时导出的筛选条件。如需离线审查，可通过 `GET /api/billing/admin/export-audits/export` 导出 CSV；这次导出本身也会写入一条 `exportType=EXPORT_AUDIT` 的审计记录，导出失败也会尝试写 `FAILED` 审计，形成闭环。

## 日汇总快照

`billing_daily_summary` 用于保存按天聚合后的账务指标，降低后台汇总页在流水、调用审计、支付订单变大后的实时扫描压力。

- `scopeUserId=0` 表示全局快照，真实用户 ID 表示单用户快照。
- 刷新快照时仍复用现有 `summarizeLedger`、`summarizeUsage`、`summarizeOrders` 口径，不单独发明统计规则。
- 批量刷新接口按包含首尾日期的闭区间逐日刷新，单次限制 31 天，避免一次请求长时间扫描大表。
- `GET /api/billing/admin/summary` 只有在 `from` 和 `to` 都是整日边界、且范围内每一天都有快照时，才返回 `summarySource=SNAPSHOT` 并读取快照。
- 如果快照缺失、日期不是整日边界、未传时间范围，接口返回 `summarySource=LIVE`，继续走实时聚合，避免后台看到不完整数据。
- 钱包余额仍读取实时 `user_wallet`，因为余额是当前状态，不是日区间指标。

## 个人供应商凭据

`user_provider_credential` 用于保存用户自己的供应商调用凭据，例如让 A 用户使用 A 自己的 Liblib/OpenAI Key，B 用户使用 B 自己的 Key。

- `GET /api/provider-credentials`：当前登录用户查看自己的凭据摘要，只返回 `provider`、`credentialName`、`enabled`、`payloadKeys`，不会返回密钥值。
- `PUT /api/provider-credentials/{provider}/{credentialName}`：当前登录用户保存或更新自己的凭据，body 中的 `payload` 会加密后写入数据库。
- 保存凭据前必须配置 `app.billing.credential-secret`，否则接口会拒绝保存，避免把真实 API Key 明文或弱保护写入库里。
- `UserProviderCredentialService.resolveCredential(userId, provider, credentialName)` 是供应商调用链读取个人凭据的内部入口；解析结果只在服务端内存中使用，不对前端返回。
- `LiblibLoraAgent` 已接入当前登录用户上下文：调用时优先读取当前用户 `provider=liblib, credentialName=default` 的 `accessKey` / `secretKey`；用户凭据缺失、禁用或字段不完整时，回退平台级 `app.liblib.access-key` / `app.liblib.secret-key`。
- `GptImageAgent` 已接入当前登录用户上下文：调用时优先读取当前用户 `provider=gpt-image, credentialName=default` 的 `apiKey`，可选读取 `baseUrl`；用户凭据缺失、禁用或字段不完整时，回退平台级 `app.gpt-image.api-keys` / `app.gpt-image.base-url`。
- 其他供应商后续接入时应沿用同一规则：先查当前用户凭据，缺失时再按平台策略决定是否回退平台 Key。

## 一致性检查

`BillingReconciliationService` 提供只读检查，不自动修账。当前检查四类核心关系：

- `WALLET_BALANCE`：钱包余额应等于已入账流水中 `IN + OUT` 的积分合计。
- `WALLET_FROZEN`：冻结积分应等于仍在 `STARTED` 状态的调用预估积分合计。
- `PAID_ORDER_LEDGER`：已支付订单数应等于支付入账流水数。
- `USAGE_CHARGE_LEDGER`：实际扣费大于 0 的成功调用数应等于生成扣费流水数。

如果检查发现差异，先用异常明细接口定位具体问题行，再导出相关流水、订单和调用审计做人工确认，最后通过受控的补单、人工入账、退款或手工处理流程修复。不要让一致性检查接口直接修改钱包。

异常明细目前覆盖四类最常见的大表问题，既能查“业务表缺流水”，也能查“流水缺业务表”：

- `PAID_ORDER_LEDGER`：列出状态为 `PAID`，但缺少 `idempotencyKey=payment:{orderNo}` 的 `PAYMENT_RECHARGE` 已入账流水的订单。
- `PAYMENT_LEDGER_ORDER`：列出 `PAYMENT_RECHARGE` 已入账流水，但找不到对应 `PAID` 订单的流水。
- `USAGE_CHARGE_LEDGER`：列出状态为 `SUCCEEDED` 且 `actualPoints > 0`，但缺少同 `usageLogId` 的 `GENERATION_CHARGE` 已入账流水的调用记录。
- `CHARGE_LEDGER_USAGE`：列出 `GENERATION_CHARGE` 已入账流水，但找不到对应成功调用记录的流水。

异常明细接口按 `id desc` 返回，并支持 `cursor`；前端或运营工具应把上一次响应的 `nextCursor` 传给下一次请求，避免在超大表上使用大 offset。

`billing_reconciliation_anomaly_action` 只保存人工处理状态，不参与钱包、流水、订单和调用审计的金额计算。异常指纹固定为 `type + ":" + sourceId`，其中 `sourceId` 是异常明细里的业务行 ID。同一个异常再次提交处理状态时会更新原记录，不会重复插入。建议运营上按以下口径使用：

- `ACKNOWLEDGED`：已经看到并确认需要后续处理。
- `IGNORED`：确认是可接受差异或历史数据，不再作为当前处理重点。
- `RESOLVED`：已经通过补单、补流水、退款或其他受控流程处理完成。
- `OPEN`：重新打开此前已处理或已忽略的异常。

异常处理记录和异常明细查询保持解耦：异常明细仍从原始大表按游标扫描，处理记录单独按 `type/status/userId/id` 查询。`GET /api/billing/admin/reconciliation/anomalies` 返回的每个异常项会额外带上 `anomalyKey`、`actionStatus`、`actionNote` 和 `actionUpdatedAt`，后端只对当前页最多 200 条异常指纹批量查询处理记录后合并，不在原始异常查询里 join 大表。运营前端可以直接用这些字段展示处理状态；如果要单独看某类已忽略、已处理的记录，再调用 `GET /api/billing/admin/reconciliation/anomaly-actions`。

`billing_reconciliation_run` 用于保存每次巡检的运行记录：

- `scopeUserId=0` 表示全局巡检，真实用户 ID 表示单用户巡检。
- `triggerType` 目前支持手动传入 `MANUAL` 或 `SCHEDULED` 等字符串，后续接定时任务时不需要改表结构。
- `status=SUCCESS/FAILED` 表示本次巡检是否成功完成；巡检本身成功但发现账务差异时仍是 `SUCCESS`，通过 `healthy=false` 和 `anomalyCount>0` 表示账务不健康。
- `anomalyCount` 保存本次检查项差异值的合计，用于后台排序和快速判断严重程度；具体差异仍以 `reportJson` 和异常明细接口为准。
- `triggeredByUserId` 保存发起巡检的管理员 ID；后续系统定时任务触发时可以为空或写固定系统账号。
- 这个表只记录巡检结果，不修账、不入账、不改变钱包，避免把诊断能力和资金变更耦合在一起。

定时巡检已经接入 `BillingReconciliationScheduler`，默认不开启。开启 `app.billing.reconciliation-schedule-enabled=true` 后，会按 `app.billing.reconciliation-cron` 巡检。`app.billing.reconciliation-schedule-mode=GLOBAL` 会跑一次全局巡检，并调用 `BillingReconciliationRunService.runAndRecord(null, "SCHEDULED", null)` 写入运行记录；`USER_BATCH` 会按 `user_wallet.userId` keyset 分页，每次调度只读取 `app.billing.reconciliation-user-batch-size` 个钱包用户，逐个调用 `runAndRecord(userId, "SCHEDULED_USER_BATCH", null)`，再把本批最后一个 `userId` 保存到 `billing_scheduler_state.lastUserId`。下一次调度从该游标继续；到达末尾时清空游标，下一轮从头开始。分批模式会先写入 `leaseOwner/leaseUntil` 作为调度租约，租约未过期时其他实例跳过本轮；本批结束后释放租约，服务异常退出时依靠 `leaseUntil` 过期恢复。后台可通过 `GET /api/billing/admin/scheduler-states` 查看 `leaseActive`、`lastUserId`、`leaseOwner` 和 `updatedAt`，定位游标停滞或租约未释放的问题。巡检失败时由 `runAndRecord` 先尝试保存失败记录，调度器再吞掉异常，避免一次数据库或统计异常让后续定时任务停止；分批模式下单个用户失败不会阻断后续用户。

## 大表索引

账务三张大表已经声明复合索引，主要覆盖用户隔离、筛选、游标分页和一致性检查：

- `wallet_ledger`
  - `userId, id`：用户侧流水游标分页。
  - `userId, createdAt, id`：用户侧时间范围查询。
  - `userId, type, direction, id`：按流水类型和方向筛选。
  - `status, type, userId`：一致性检查和后台按类型统计。
  - `type, direction, id`：管理员全局流水列表、导出按类型/方向做 keyset 查询。
  - `type, status, id`：支付入账流水、生成扣费流水的反向异常明细查询。
- `generation_usage_log`
  - `userId, id`：用户侧调用审计游标分页。
  - `userId, startedAt, id`：按开始时间筛选。
  - `userId, status, provider, mode, id`：按状态、供应商、模式筛选。
  - `status, userId`：检查未完成调用冻结量和成功调用扣费关系。
  - `status, provider, mode, id`：管理员全局调用审计列表、导出按状态/供应商/模式做 keyset 查询。
  - `provider, providerTaskId, id`：调用审计列表和导出里的供应商任务摘要筛选；精确反查使用 `generation_provider_task_ref`。
  - `costSource, id`：区分预估扣费、明确积分扣费和供应商上报消耗，便于运营审计。
- `generation_provider_task_ref`
  - `provider, providerTaskId, id`：按供应商任务号精确定位本地调用记录，避免扫描 `generation_usage_log` 的逗号拼接摘要字段。
  - `usageLogId, id`：从一条调用审计反查它关联的所有供应商任务号。
  - `userId, id`：后续按用户做供应商任务号审计、导出或清理。
- `payment_order`
  - `userId, id`：用户侧订单游标分页。
  - `userId, createdAt, id`：按创建时间筛选。
  - `userId, status, provider, id`：按状态和支付渠道筛选。
  - `status, userId`：检查已支付订单和入账流水关系。
  - `status, provider, id`：管理员全局订单列表、导出按状态/支付渠道做 keyset 查询。
  - `status, userId, id`：已支付订单与支付入账流水的一致性检查和异常定位。
- `billing_daily_summary`
  - `scopeUserId, summaryDate`：后台按用户或全局读取连续日期快照。
  - `summaryDate, scopeUserId`：后续后台批量巡检、重算和离线任务按日期扫描。
- `billing_reconciliation_run`
  - `scopeUserId, id`：后台按用户或全局巡检记录做游标分页。
  - `status, id`：按运行状态筛选成功或失败的巡检记录。
  - `triggerType, id`：区分手动巡检、定时巡检和后续批处理巡检。
  - `startedAt, id`：按运行时间范围筛选，支撑后台运营查看历史巡检。
- `billing_reconciliation_anomaly_action`
  - `anomalyKey`：按 `type + ":" + sourceId` 幂等更新某条异常的人工处理状态。
  - `type, status, id`：管理员按异常类型和处理状态做游标分页。
  - `userId, type, status, id`：管理员查看某个用户的异常处理记录。
  - `updatedAt, id`：后台按最近处理时间排查人工处理进展。
- `billing_export_audit_log`
  - `exportId`：单次导出的唯一编号，便于排查某次导出行为。
  - `operatorUserId, id`：按操作者查看历史导出。
  - `exportType, status, id`：按导出类型和状态做运营审计。
  - `startedAt, id`：按导出时间范围排查敏感数据导出行为。
- `billing_scheduler_state`
  - `stateKey`：调度状态唯一键，当前用于保存 `BILLING_RECONCILIATION_USER_BATCH` 游标。
  - `leaseUntil`：分批调度租约过期时间，避免多实例同时推进游标。
  - `updatedAt`：后台查看调度状态更新时间，定位卡住或长期未推进的调度任务。

新部署时 Hibernate 会按实体声明创建索引。已有线上库如果数据量较大，建议在低峰期用数据库迁移脚本显式建索引，并先检查是否已存在同名索引，避免自动 DDL 在高峰期锁表。SQLite 主库可以参考 `docs/billing-sqlite-migration-20260710.sql`，先备份，再按缺失列和缺失索引逐项执行。

`POST /api/billing/payment-orders` 示例：

```json
{
  "points": 100,
  "amountCents": 19900,
  "provider": "manual",
  "idempotencyKey": "client-request-uuid"
}
```

`POST /api/billing/admin/payment-orders/{orderNo}/paid` 示例：

```json
{
  "providerOrderNo": "wx-or-alipay-trade-no",
  "paidAmountCents": 19900
}
```

## 计价配置

默认不启用扣费：

```yaml
app:
  billing:
    charge-enabled: false
    callback-token: "${BILLING_CALLBACK_TOKEN:}"
    credential-secret: "${BILLING_CREDENTIAL_SECRET:}"
    export-max-rows: 10000
    reconciliation-schedule-enabled: false
    reconciliation-cron: "0 0 3 * * *"
    reconciliation-schedule-mode: "GLOBAL"
    reconciliation-user-batch-size: 100
    reconciliation-lease-seconds: 900
```

后续需要开启预估冻结时，可以先按统一点数配置：

```yaml
app:
  billing:
    charge-enabled: true
    default-image-points: 10
    default-video-base-points: 80
    default-video-second-points: 5
    image-agent-points:
      liblib-lora: 12
      gpt-image: 10
    video-model-base-points:
      doubao-seedance: 90
```

规则说明：

- 图片：`图片数量 * 单张点数`。
- 视频：`模型基础点数 + 秒数 * 每秒点数`。
- 覆盖规则支持前缀匹配，例如 `doubao-seedance` 可以匹配 `doubao-seedance-2-0-260128`。

## 后续接真实支付

真实微信、支付宝或其他支付平台接入时，新增回调接口后只需要复用现有服务：

1. 校验支付平台签名。
2. 用本地订单号查 `payment_order`。
3. 校验回调金额、支付状态和渠道订单号。
4. 调用 `PaymentOrderService.markPaid(orderNo, providerOrderNo, paidAmountCents)`。

真实回调也必须保持幂等：同一个支付订单已经是 `PAID` 时直接返回成功，不再次入账。

当前占位实现是 `PaymentCallbackService` + `PaymentCallbackController`：

- 默认 `app.billing.callback-token` 为空，回调入口返回不可用，避免未配置时被外部调用入账。
- 配置 token 后，`manual` 渠道外部请求必须传 `X-Billing-Callback-Token`。
- `PaymentCallbackService` 只按 `provider` 选择 `PaymentCallbackVerifier`，再解析订单号、渠道订单号和支付金额。
- 控制器只负责 HTTP 入口和错误响应，真正入账仍复用 `PaymentOrderService.markPaid`。
- 后续接微信、支付宝时，应新增对应 `PaymentCallbackVerifier` 做平台签名验签，但不要绕过 `PaymentOrderService`。
- 未注册验签器的 `provider` 会被拒绝，避免未知渠道误入账。

## 后续接真实供应商消耗

现在生成任务成功时如果仍调用 `markGenerationSucceeded(usageLogId, 0)`，账务服务会按预估积分结算，并把 `costSource` 写成 `ESTIMATED_FALLBACK`。如果供应商返回真实消耗或任务号，应调用：

```java
billingService.markGenerationSucceeded(usageLogId, new BillingService.ProviderCost(
        actualPoints,
        providerTaskId,
        providerRawCost,
        providerRawUnit,
        costSource,
        exchangeRate
));
```

字段含义：

- `actualPoints`：换算后真正扣用户的积分；大于 0 时优先使用该值，等于 0 时回退预估积分。
- `providerTaskId`：供应商侧任务号，例如 Liblib 的 `generateUuid`，用于和供应商后台账单互查。
- `providerRawCost` / `providerRawUnit`：供应商原始消耗和单位，例如积分、点数、token 或金额单位。
- `costSource`：消耗来源，建议使用 `PROVIDER_REPORTED`、`EXPLICIT_POINTS`、`ESTIMATED_FALLBACK` 这类稳定枚举字符串。
- `exchangeRate`：原始消耗换算成本平台积分的比例，方便后续审计换算规则。

这个框架先保证“成功调用、扣费流水、供应商账单证据”能落到同一条 `generation_usage_log` 上；具体每个供应商怎么换算，可以在对应 Agent 或调用编排层拿到返回值后再传入 `ProviderCost`。

当前 Liblib LoRA 链路已经把创建任务时返回的 `generateUuid` 写入 `GenerationProviderCostContext`，任务成功结算时由 `TaskService` 自动读取并传给 `BillingService.ProviderCost`。同一个平台任务里如果生成多张图，`generation_usage_log.providerTaskId` 会按提交顺序去重后用逗号拼接，作为列表和导出的展示摘要；账务服务会同步拆分写入 `generation_provider_task_ref`，精确反查请走 `GET /api/billing/admin/provider-task-ref`。任务线程开始和结束都会清理该上下文，避免线程池复用时串到其他用户或其他任务。

仍建议继续补：

- 管理端账务页面：已经支持钱包余额、运营汇总、流水、调用审计、支付订单、人工入账、确认支付和 CSV 导出。
- 支付回调验签模块：微信、支付宝或你最终选定的平台。
- 各供应商真实消耗换算规则：在 Agent 或调用编排层把供应商返回的原始单位换算成 `ProviderCost.actualPoints`。
- 分片巡检并发控制：当前 `USER_BATCH` 已通过 `billing_scheduler_state` 持久化游标并使用租约避免多个实例同时推进同一个游标；后续如果改成多节点高频调度，可以继续增强为数据库条件更新或专用分布式锁。
- 个人 API Key 路由：Liblib 和 GPT-Image 已优先使用用户自己的供应商凭据，失败时再按平台策略处理；后续新增供应商继续复用 `UserProviderCredentialService`。
