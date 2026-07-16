const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = (process.env.AI_STUDIO_BASE_URL || '').replace(/\/$/, '');
const username = process.env.AI_STUDIO_SMOKE_USERNAME || '';
const password = process.env.AI_STUDIO_SMOKE_PASSWORD || '';
if (!baseUrl) {
    console.error('请设置 AI_STUDIO_BASE_URL，例如 http://127.0.0.1:5020');
    process.exit(1);
}

function fail(message) {
    throw new Error(message);
}

async function expectStatus(requestPath, expectedStatus, options) {
    const response = await fetch(`${baseUrl}${requestPath}`, options);
    if (response.status !== expectedStatus) {
        fail(`${requestPath} 期望 HTTP ${expectedStatus}，实际 ${response.status}`);
    }
    return response;
}

function localStaticAssets() {
    const root = path.resolve(__dirname, '..');
    const html = fs.readFileSync(path.join(root, 'frontend', 'index.html'), 'utf8');
    const firstInlineScript = html.indexOf('<script>');
    const staticMarkup = firstInlineScript >= 0 ? html.slice(0, firstInlineScript) : html;
    const assets = new Set(['/index.html']);
    for (const match of staticMarkup.matchAll(/<(?:script|link)\b[^>]+(?:src|href)\s*=\s*(["'])([^"']+)\1/gi)) {
        const asset = match[2];
        if (!asset.startsWith('http://') && !asset.startsWith('https://') && !asset.startsWith('//')) {
            assets.add(`/${asset.split(/[?#]/, 1)[0]}`);
        }
    }
    return [...assets];
}

function controllerRoutes() {
    const root = path.resolve(__dirname, '..');
    const controllerDir = path.join(root, 'src', 'main', 'java', 'com', 'elebusiness', 'controller');
    const routes = new Map();
    for (const file of fs.readdirSync(controllerDir).filter(name => name.endsWith('.java'))) {
        const source = fs.readFileSync(path.join(controllerDir, file), 'utf8');
        const classIndex = source.indexOf('class ');
        const preamble = classIndex >= 0 ? source.slice(0, classIndex) : '';
        const classRoute = [...preamble.matchAll(/@RequestMapping\(\s*["']([^"']*)["']\s*\)/g)].at(-1)?.[1] || '';
        for (const match of source.matchAll(/@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*(?:value\s*=\s*)?["']([^"']*)["'][^)]*\))?/g)) {
            const method = match[1].toUpperCase();
            const route = `${classRoute}${match[2] || ''}`.replace(/\{[^}]+\}/g, 'smoke-id');
            if (route.startsWith('/api/')) routes.set(`${method} ${route}`, { method, route });
        }
    }
    return [...routes.values()];
}

function isPublicRoute(route) {
    return route.startsWith('/api/auth/')
        || route === '/api/prompts'
        || route.startsWith('/api/prompts/')
        || route === '/api/categories/index'
        || route === '/api/config/status'
        || route === '/api/agents'
        || route.startsWith('/api/billing/payment-callbacks/');
}

async function run() {
    for (const asset of localStaticAssets()) {
        await expectStatus(asset, 200);
    }

    const publicEndpoints = [
        '/api/auth/check',
        '/api/prompts',
        '/api/prompts/search',
        '/api/categories/index',
        '/api/config/status',
        '/api/agents'
    ];
    for (const endpoint of publicEndpoints) {
        await expectStatus(endpoint, 200);
    }

    const protectedEndpoints = [
        ['/api/session/current', { method: 'GET' }],
        ['/api/settings', { method: 'GET' }],
        ['/api/history/conversations', { method: 'GET' }],
        ['/api/custom_generate', { method: 'POST' }]
    ];
    for (const [endpoint, options] of protectedEndpoints) {
        await expectStatus(endpoint, 401, options);
    }

    const allProtectedRoutes = controllerRoutes().filter(({ route }) => !isPublicRoute(route));
    for (const { method, route } of allProtectedRoutes) {
        const response = await fetch(`${baseUrl}${route}`, { method });
        if (response.status !== 401) {
            fail(`${method} ${route} 未登录时应返回 401，实际 ${response.status}`);
        }
    }

    const callbackResponse = await fetch(`${baseUrl}/api/billing/payment-callbacks/manual`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: 'PAID' })
    });
    if (callbackResponse.status === 401 || callbackResponse.status === 404) {
        fail(`支付回调入口不可达，HTTP ${callbackResponse.status}`);
    }

    const executablePath = path.join(
        __dirname,
        'browsers',
        'chromium_headless_shell-1223',
        'chrome-headless-shell-win64',
        'chrome-headless-shell.exe'
    );
    const browser = await chromium.launch({
        headless: true,
        executablePath: fs.existsSync(executablePath) ? executablePath : undefined,
        args: ['--no-proxy-server']
    });
    try {
        const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
        if (username && password) {
            const login = await context.request.post(`${baseUrl}/api/auth/login`, {
                data: { username, password }
            });
            if (!login.ok()) fail(`浏览器 smoke 登录失败: HTTP ${login.status()}`);
        }
        const page = await context.newPage();
        if (!username || !password) {
            await page.route('**/api/kaipin_materials?*', route => route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify({ items: [] })
            }));
        }
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        page.on('console', message => {
            if (message.type() === 'error') errors.push(message.text());
        });
        await page.goto(`${baseUrl}/index.html`, { waitUntil: 'networkidle', timeout: 30000 });
        const ui = await page.evaluate(async () => {
            const result = { errors: [] };
            try { window.openCustomCatModal(); } catch (error) { result.errors.push(String(error)); }
            result.customCategoryItems = document.querySelectorAll('#customCatPanels .ec-cascader-item').length;
            try { window.openEcommerceModal(); } catch (error) { result.errors.push(String(error)); }
            await new Promise(resolve => setTimeout(resolve, 30));
            try { window.ecToggleCascader({ stopPropagation() {} }); } catch (error) { result.errors.push(String(error)); }
            result.ecommerceCategoryItems = document.querySelectorAll('#ec-cat-panels .ec-cascader-item').length;
            for (const mode of ['custom', 'ecommerce', 'product']) {
                try { window.setMode(mode); } catch (error) { result.errors.push(String(error)); }
                const activeModes = document.querySelectorAll('.generation-toolbar .mode-btn.active');
                if (activeModes.length !== 1 || activeModes[0].id !== `btn-${mode}`) {
                    result.errors.push(`${mode} 模式激活状态异常`);
                }
                const modeButtons = document.querySelectorAll('.generation-toolbar .mode-btn');
                if ([...modeButtons].some(button => button.getAttribute('aria-pressed') !== String(button.id === `btn-${mode}`))) {
                    result.errors.push(`${mode} 模式无障碍选中状态异常`);
                }
            }
            try {
                await window.openKaiPinMaterialDb();
                result.materialViewOpened = document.getElementById('kpMaterialView')?.classList.contains('active') === true;
                window.closeKaiPinMaterialDb();
                result.materialViewClosed = document.getElementById('kpMaterialView')?.classList.contains('active') === false;
            } catch (error) {
                result.errors.push(String(error));
            }
            return result;
        });
        if (ui.errors.length) fail(`类目 UI 调用失败: ${ui.errors.join('; ')}`);
        if (ui.customCategoryItems < 1) fail('自定义模式产品类目为空');
        if (ui.ecommerceCategoryItems < 1) fail('电商模式产品类目为空');
        if (errors.length) fail(`页面控制台错误: ${errors.join('; ')}`);
        if (!ui.materialViewOpened || !ui.materialViewClosed) fail('开品素材库打开或关闭状态异常');
        console.log(`server smoke checks passed: ${localStaticAssets().length} static assets, ${allProtectedRoutes.length} protected routes, ${ui.customCategoryItems} custom categories, ${ui.ecommerceCategoryItems} ecommerce categories`);
    } finally {
        await browser.close();
    }
}

run().catch(error => {
    console.error(error.message);
    process.exit(1);
});
