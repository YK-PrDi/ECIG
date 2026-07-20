const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = (process.env.AI_STUDIO_BASE_URL || 'http://127.0.0.1:5021').replace(/\/$/, '');
const username = process.env.AI_STUDIO_SMOKE_USERNAME || 'admin';
const password = process.env.AI_STUDIO_SMOKE_PASSWORD || '123456';

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
    const context = await browser.newContext();
    const login = await context.request.post(`${baseUrl}/api/auth/login`, {
      data: { username, password }
    });
    const loginBody = await login.json();
    assert.ok(login.ok() && loginBody.success, '测试账号登录失败');

    const page = await context.newPage();
    const pageErrors = [];
    const calls = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    await page.route('https://fonts.googleapis.com/**', route => route.abort());
    await page.route('https://fonts.gstatic.com/**', route => route.abort());
    await page.route('**/api/custom_analyze', route => {
      calls.push('analyze');
      return route.fulfill({ status: 500, body: '{"error":"不应调用分析接口"}' });
    });
    await page.route('**/api/custom_generate', route => {
      calls.push('generate');
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ taskId: 'mock-skip-analysis-task' })
      });
    });
    await page.route('**/api/task/mock-skip-analysis-task', route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        taskId: 'mock-skip-analysis-task',
        status: 'stopped',
        progress: 0,
        total: 1,
        results: []
      })
    }));

    await page.goto(`${baseUrl}/index.html`, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForFunction(() => typeof executeTask === 'function');
    const result = await page.evaluate(async () => {
      setMode('custom');
      document.getElementById('skipGeminiToggle').checked = true;
      document.getElementById('customTextOverlay').checked = false;
      document.getElementById('customStyle').value = 'original';
      document.getElementById('promptInput').value = '请生成白底图，保持产品原样';
      droppedFiles = [new File(['image'], 'product.png', { type: 'image/png' })];
      await executeTask();
      return {
        buttonDisabled: document.getElementById('generateBtn').disabled,
        submitting: window.__taskSubmitting
      };
    });

    assert.deepStrictEqual(calls, ['generate'], '跳过分析时应直接且仅调用生成接口');
    assert.strictEqual(result.buttonDisabled, false, '提交完成后发送按钮必须恢复');
    assert.strictEqual(result.submitting, false, '提交完成后不能遗留阻塞状态');
    assert.deepStrictEqual(pageErrors, [], `页面出现脚本错误: ${pageErrors.join('; ')}`);
    console.log('skip analysis submit check passed');
  } finally {
    await browser.close();
  }
}

run().catch(error => {
  console.error(error.message);
  process.exit(1);
});
