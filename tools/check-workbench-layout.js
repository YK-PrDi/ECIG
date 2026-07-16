const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = (process.env.AI_STUDIO_BASE_URL || '').replace(/\/$/, '');
const username = process.env.AI_STUDIO_SMOKE_USERNAME || '';
const password = process.env.AI_STUDIO_SMOKE_PASSWORD || '';

if (!baseUrl || !username || !password) {
    console.error('请设置 AI_STUDIO_BASE_URL、AI_STUDIO_SMOKE_USERNAME、AI_STUDIO_SMOKE_PASSWORD');
    process.exit(1);
}

const viewports = [
    { name: 'desktop', width: 1440, height: 900 },
    { name: 'laptop', width: 1280, height: 720 },
    { name: 'tablet', width: 1024, height: 768 },
    { name: 'mobile', width: 390, height: 844 }
];

function overlaps(left, right) {
    if (!left || !right || left.width === 0 || right.width === 0) return false;
    return left.left < right.right && left.right > right.left
        && left.top < right.bottom && left.bottom > right.top;
}

function fail(message) {
    throw new Error(message);
}

async function login(context) {
    const response = await context.request.post(`${baseUrl}/api/auth/login`, {
        data: { username, password }
    });
    if (!response.ok()) fail(`登录失败：HTTP ${response.status()}`);
    const body = await response.json();
    if (!body.success) fail('登录接口未返回成功状态');
}

async function run() {
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
    const outputDir = path.resolve(__dirname, '..', 'target', 'workbench-screenshots');
    fs.mkdirSync(outputDir, { recursive: true });

    try {
        const context = await browser.newContext();
        await login(context);

        for (const viewport of viewports) {
            const page = await context.newPage();
            const errors = [];
            page.on('pageerror', error => errors.push(error.message));
            page.on('console', message => {
                if (message.type() === 'error') errors.push(message.text());
            });

            await page.setViewportSize({ width: viewport.width, height: viewport.height });
            await page.goto(`${baseUrl}/index.html`, { waitUntil: 'networkidle', timeout: 30000 });
            await page.evaluate(() => window.setMode('custom'));
            await page.waitForTimeout(200);

            const geometry = await page.evaluate(() => {
                const rect = element => {
                    const box = element?.getBoundingClientRect();
                    return box ? {
                        left: box.left,
                        right: box.right,
                        top: box.top,
                        bottom: box.bottom,
                        width: box.width,
                        height: box.height
                    } : null;
                };
                const modeSelector = document.querySelector('.input-container .mode-selector');
                return {
                    canvas: rect(document.getElementById('canvasPanel')),
                    chat: rect(document.getElementById('chatPanel')),
                    generate: rect(document.getElementById('generateBtn')),
                    agent: rect(document.getElementById('agentSelectWrapper')),
                    modeSelector: rect(modeSelector),
                    modeSelectorClientWidth: modeSelector?.clientWidth || 0,
                    modeSelectorScrollWidth: modeSelector?.scrollWidth || 0,
                    customControls: {
                        style: rect(document.querySelector('#customConfig .custom-style-trigger')),
                        category: rect(document.querySelector('#grp-category .gf-select-header')),
                        aspect: rect(document.getElementById('customAspectWrapper')),
                        count: rect(document.querySelector('#grp-custom-count .gf-select')),
                        lora: rect(document.getElementById('grp-lora'))
                    },
                    horizontalOverflow: document.documentElement.scrollWidth > window.innerWidth + 1
                };
            });

            if (geometry.horizontalOverflow) fail(`${viewport.name} 存在页面级横向滚动`);
            if (!geometry.canvas || !geometry.chat) fail(`${viewport.name} 缺少画布或生成面板`);
            if (viewport.width >= 1024 && (geometry.canvas.width <= 260 || geometry.chat.width <= 300)) {
                fail(`${viewport.name} 主区域被过度压缩`);
            }
            if (viewport.width >= 1024 && overlaps(geometry.generate, geometry.agent)) {
                fail(`${viewport.name} 发送按钮与模型选择器重叠`);
            }
            if (!geometry.modeSelector || geometry.modeSelector.width <= 0) {
                fail(`${viewport.name} 模式选择器不可见`);
            }
            const minimumModeWidth = Math.min(280, geometry.chat.width - 24);
            if (geometry.modeSelector.width < minimumModeWidth) {
                fail(`${viewport.name} 模式选择器过窄：${geometry.modeSelector.width}px`);
            }
            if (viewport.width >= 1024
                && geometry.modeSelectorScrollWidth > geometry.modeSelectorClientWidth + 1) {
                fail(`${viewport.name} 五个模式未完整显示`);
            }
            if (viewport.width < 600 && geometry.modeSelector.right > viewport.width + 1) {
                fail(`${viewport.name} 模式选择器超出视口`);
            }
            const minimumControlWidths = { style: 120, category: 120, aspect: 120, count: 72, lora: 220 };
            for (const [name, minimum] of Object.entries(minimumControlWidths)) {
                const control = geometry.customControls[name];
                if (!control || control.width < minimum) {
                    fail(`${viewport.name} 自定义配置 ${name} 宽度不足：${control?.width || 0}px`);
                }
            }
            if (errors.length) fail(`${viewport.name} 控制台错误：${errors.join('; ')}`);

            await page.screenshot({
                path: path.join(outputDir, `${viewport.name}.png`),
                fullPage: true
            });
            await page.close();
        }
        console.log(`workbench layout checks passed: ${viewports.length} viewports`);
    } finally {
        await browser.close();
    }
}

run().catch(error => {
    console.error(error.message);
    process.exit(1);
});
