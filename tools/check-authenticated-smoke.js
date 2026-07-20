const baseUrl = (process.env.AI_STUDIO_BASE_URL || '').replace(/\/$/, '');
const username = process.env.AI_STUDIO_SMOKE_USERNAME || '';
const password = process.env.AI_STUDIO_SMOKE_PASSWORD || '';

if (!baseUrl || !username || !password) {
    console.error('请设置 AI_STUDIO_BASE_URL、AI_STUDIO_SMOKE_USERNAME、AI_STUDIO_SMOKE_PASSWORD');
    process.exit(1);
}

function fail(message) {
    throw new Error(message);
}

async function request(path, options = {}) {
    const response = await fetch(`${baseUrl}${path}`, options);
    if (response.status === 401 || response.status === 403) fail(`${path} 登录后仍无权访问: HTTP ${response.status}`);
    return response;
}

async function run() {
    const loginResponse = await fetch(`${baseUrl}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
    });
    if (!loginResponse.ok) fail(`登录接口失败: HTTP ${loginResponse.status}`);
    const loginBody = await loginResponse.json();
    if (!loginBody.success) fail('临时管理员登录失败');
    const cookie = loginResponse.headers.get('set-cookie')?.split(';', 1)[0];
    if (!cookie) fail('登录成功但未返回会话 Cookie');

    const safeReadEndpoints = [
        '/api/session/current',
        '/api/session/list',
        '/api/history/conversations',
        '/api/history/generations?size=10',
        '/api/products',
        '/api/gallery?path=',
        '/api/kaipin_materials?limit=10',
        '/api/provider-credentials',
        '/api/settings',
        '/api/billing/wallet',
        '/api/billing/ledger',
        '/api/billing/usage',
        '/api/billing/payment-orders',
        '/api/users',
        '/api/billing/admin/wallet',
        '/api/billing/admin/ledger',
        '/api/billing/admin/usage',
        '/api/billing/admin/payment-orders',
        '/api/billing/admin/summary',
        '/api/billing/admin/reconciliation',
        '/api/billing/admin/reconciliation/anomalies',
        '/api/billing/admin/reconciliation/runs',
        '/api/billing/admin/scheduler-states',
        '/api/billing/admin/export-audits',
        '/api/billing/admin/reconciliation/anomaly-actions',
        '/api/billing/admin/provider-task-ref'
    ];
    const expectedDegradedStatuses = new Map([
        // 未配置钉钉返回 503；已配置但钉钉上游不可用返回 502。
        ['/api/products', new Set([502, 503])]
    ]);

    for (const endpoint of safeReadEndpoints) {
        const response = await request(endpoint, { headers: { Cookie: cookie } });
        const allowedDegraded = expectedDegradedStatuses.get(endpoint) || new Set();
        if (!response.ok && response.status !== 400 && response.status !== 404 && !allowedDegraded.has(response.status)) {
            fail(`${endpoint} 返回异常状态: HTTP ${response.status}`);
        }
    }

    await request('/api/auth/heartbeat', { headers: { Cookie: cookie } });
    console.log(`authenticated smoke checks passed: ${safeReadEndpoints.length} logged-in read endpoints`);
}

run().catch(error => {
    console.error(error.message);
    process.exit(1);
});
