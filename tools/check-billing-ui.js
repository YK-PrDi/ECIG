const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'frontend', 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(root, 'frontend', 'index.css'), 'utf8');

function fail(message) {
  console.error(message);
  process.exitCode = 1;
}

const requiredHtml = [
  'id="billingAdminCard"',
  'id="billingTargetUserId"',
  'id="billingFromTime"',
  'id="billingToTime"',
  'id="billingLedgerType"',
  'id="billingUsageStatus"',
  'id="billingOrderStatus"',
  'id="billingProviderFilter"',
  'id="billingWalletSummary"',
  'id="billingAdminSummary"',
  'id="billingReconcileBtn"',
  'id="billingReconciliationPanel"',
  'id="billingLoadMoreLedgerBtn"',
  'id="billingLoadMoreUsageBtn"',
  'id="billingLoadMorePaymentBtn"',
  'id="billingLedgerTable"',
  'id="billingUsageTable"',
  'id="billingPaymentTable"',
  'id="billingCreditPoints"',
  'id="billingPaidOrderNo"',
  'function loadBillingAdminData',
  'function loadMoreBillingAdminData',
  'function updateBillingLoadMoreButtons',
  'function renderBillingWallet',
  'function renderBillingAdminSummary',
  'function runBillingReconciliation',
  'function renderBillingReconciliation',
  'function renderBillingLedger',
  'function renderBillingUsage',
  'function renderBillingPaymentOrders',
  'function exportBillingAdminCsv',
  'function submitAdminCredit',
  'function markBillingOrderPaid',
  '/api/billing/admin/wallet',
  '/api/billing/admin/summary',
  '/api/billing/admin/reconciliation',
  '/api/billing/admin/ledger',
  '/api/billing/admin/ledger/export',
  '/api/billing/admin/usage',
  '/api/billing/admin/usage/export',
  '/api/billing/admin/payment-orders',
  '/api/billing/admin/payment-orders/export',
  '/api/billing/admin/credit'
];

for (const needle of requiredHtml) {
  if (!html.includes(needle)) {
    fail(`billing UI missing ${needle}`);
  }
}

const requiredCss = [
  '.billing-admin-grid',
  '.billing-filter-grid',
  '.billing-wallet-summary',
  '.billing-admin-summary',
  '.billing-reconciliation-panel',
  '.billing-reconciliation-summary',
  '.billing-panel-toolbar',
  '.billing-table',
  '.billing-tabs',
  '.billing-tab.active',
  '.billing-empty'
];

for (const needle of requiredCss) {
  if (!css.includes(needle)) {
    fail(`billing CSS missing ${needle}`);
  }
}

if (process.exitCode) {
  process.exit(process.exitCode);
}

console.log('billing UI checks passed');
