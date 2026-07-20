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

    try {
        const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
        const login = await context.request.post(`${baseUrl}/api/auth/login`, {
            data: { username, password }
        });
        const loginBody = await login.json();
        if (!login.ok() || !loginBody.success) throw new Error('多任务检查登录失败');

        const page = await context.newPage();
        await page.route('https://fonts.googleapis.com/**', route => route.abort());
        await page.route('https://fonts.gstatic.com/**', route => route.abort());
        await page.goto(`${baseUrl}/index.html`, { waitUntil: 'commit', timeout: 30000 });
        await page.waitForSelector('#generateBtn', { state: 'visible', timeout: 30000 });
        await page.waitForFunction(() => typeof beginGenerationTask === 'function', null, { timeout: 30000 });
        await page.evaluate(() => {
            const originalFetch = window.fetch.bind(window);
            window.__mockTaskStops = [];
            window.__mockTaskStates = {
                'mock-task-1': { status: 'running', progress: 0, total: 2, results: [] },
                'mock-task-2': { status: 'running', progress: 0, total: 2, results: [] }
            };
            window.fetch = (input, options = {}) => {
                const url = typeof input === 'string' ? input : input.url;
                const match = url.match(/\/api\/task\/(mock-task-[12])(\/stop)?$/);
                if (!match) return originalFetch(input, options);
                const taskId = match[1];
                if (match[2]) {
                    window.__mockTaskStops.push(taskId);
                    window.__mockTaskStates[taskId].status = 'stopping';
                    return Promise.resolve(new Response(JSON.stringify({ success: true, status: 'stopping' }), {
                        status: 200,
                        headers: { 'Content-Type': 'application/json' }
                    }));
                }
                return Promise.resolve(new Response(JSON.stringify({
                    taskId,
                    ...window.__mockTaskStates[taskId]
                }), {
                    status: 200,
                    headers: { 'Content-Type': 'application/json' }
                }));
            };
            const emptyState = document.getElementById('emptyState');
            if (emptyState) emptyState.style.display = 'none';
            beginGenerationTask('mock-task-1');
            beginGenerationTask('mock-task-2');
        });

        await page.waitForFunction(() => document.querySelectorAll('.progress-card').length === 2);
        const initial = await page.evaluate(() => ({
            cards: document.querySelectorAll('.progress-card').length,
            generateDisabled: document.getElementById('generateBtn').disabled,
            generateIsStop: document.getElementById('generateBtn').classList.contains('is-stop'),
            firstStopDisabled: document.querySelector('.progress-card[data-task-id="mock-task-1"] .progress-stop-btn').disabled,
            secondStopDisabled: document.querySelector('.progress-card[data-task-id="mock-task-2"] .progress-stop-btn').disabled
        }));
        if (initial.cards !== 2) throw new Error('未同时显示两张任务进度卡');
        if (initial.generateDisabled || initial.generateIsStop) throw new Error('主生成按钮仍被活动任务占用');
        if (initial.firstStopDisabled || initial.secondStopDisabled) throw new Error('任务取消按钮初始状态不正确');

        await page.locator('.progress-card[data-task-id="mock-task-1"] .progress-stop-btn').click();
        await page.waitForFunction(() => window.__mockTaskStops.includes('mock-task-1'));
        await page.waitForFunction(() => {
            const title = document.querySelector('.progress-card[data-task-id="mock-task-1"] .progress-card-title');
            return title && title.textContent.includes('取消');
        });
        const stopping = await page.evaluate(() => ({
            cards: document.querySelectorAll('.progress-card').length,
            firstDisabled: document.querySelector('.progress-card[data-task-id="mock-task-1"] .progress-stop-btn').disabled,
            secondDisabled: document.querySelector('.progress-card[data-task-id="mock-task-2"] .progress-stop-btn').disabled,
            firstTitle: document.querySelector('.progress-card[data-task-id="mock-task-1"] .progress-card-title').textContent.trim()
        }));
        if (stopping.cards !== 2) throw new Error('停止请求后任务卡被提前移除');
        if (!stopping.firstDisabled || stopping.secondDisabled) throw new Error('单任务取消状态影响了其他任务');
        if (!stopping.firstTitle.includes('取消')) throw new Error('被取消任务未展示停止中状态');

        await page.screenshot({ path: path.join(__dirname, '..', 'target', 'multi-task-controls.png'), fullPage: true });

        await page.evaluate(() => { window.__mockTaskStates['mock-task-1'].status = 'stopped'; });
        await page.waitForFunction(() => !document.querySelector('.progress-card[data-task-id="mock-task-1"]'));
        if (await page.locator('.progress-card[data-task-id="mock-task-2"]').count() !== 1) {
            throw new Error('完成第一条任务时错误移除了其他任务');
        }
        await page.evaluate(() => { window.__mockTaskStates['mock-task-2'].status = 'stopped'; });
        await page.waitForFunction(() => document.querySelectorAll('.progress-card').length === 0);

        console.log('multi task controls check passed');
    } finally {
        await browser.close();
    }
}

run().catch(error => {
    console.error(error.message);
    process.exit(1);
});
