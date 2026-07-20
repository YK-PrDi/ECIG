const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = (process.env.AI_STUDIO_BASE_URL || 'http://127.0.0.1:5021').replace(/\/$/, '');
const username = process.env.AI_STUDIO_SMOKE_USERNAME || 'admin';
const password = process.env.AI_STUDIO_SMOKE_PASSWORD || '123456';
const useRealModels = process.env.AI_STUDIO_REAL_VIDEO_MODELS === 'true';

const models = [
  { id: 'veo-3.1-generate-preview', name: 'Veo 3.1', providerId: 'google', provider: 'Google', level: 0, configured: true },
  { id: 'doubao-seedance-2-0-260128', name: 'Seedance 2.0', providerId: 'seedance', provider: '火山方舟', level: 0, configured: true },
  { id: 'grok-imagine-video', name: 'Grok 文生视频', providerId: 'suixiang-grok-text', provider: 'Grok 文生视频', level: 0, inputMode: 'text_only', configured: true },
  { id: 'grok-imagine-video-1.5', name: 'Grok 图生视频', providerId: 'suixiang-grok-image', provider: 'Grok 图生视频', level: 0, inputMode: 'image_only', configured: true },
  { id: 'as-sd2.0-fast', name: '即梦 SD 2.0 Fast', providerId: 'suixiang-jimeng', provider: '即梦', level: 0, configured: true },
  { id: 'video-ds-2.0', name: '即梦 Video DS 2.0', providerId: 'suixiang-jimeng', provider: '即梦', level: 1, configured: true }
];

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
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const login = await context.request.post(`${baseUrl}/api/auth/login`, {
      data: { username, password }
    });
    const loginBody = await login.json();
    assert.ok(login.ok() && loginBody.success, '视频模型选择检查登录失败');

    const page = await context.newPage();
    const pageErrors = [];
    let submittedBody = '';
    page.on('pageerror', error => pageErrors.push(error.message));
    await page.route('https://fonts.googleapis.com/**', route => route.abort());
    await page.route('https://fonts.gstatic.com/**', route => route.abort());
    if (!useRealModels) {
      await page.route('**/api/video/models', route => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(models)
      }));
    }
    await page.route('**/api/video/generate', route => {
      submittedBody = route.request().postData() || '';
      return route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ success: false, message: '测试阻止真实视频生成' })
      });
    });

    await page.goto(`${baseUrl}/index.html`, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForFunction(() => typeof setMode === 'function' && typeof executeTask === 'function');
    await page.evaluate(() => setMode('video'));
    await page.waitForFunction(() => document.querySelectorAll('#agentSelectOptions [data-video-provider-id]').length === 5);

    const aspectOptions = await page.locator('#videoAspectRatio')
      .locator('xpath=ancestor::div[contains(@class,"gf-select")]')
      .locator('.gf-option')
      .evaluateAll(options => options.map(option => option.dataset.value));
    assert.deepStrictEqual(aspectOptions, ['16:9', '9:16', '1:1', '4:3', '3:4', '3:2', '2:3'],
      '视频比例应完整提供横屏、竖屏、方形和常用摄影比例');

    assert.strictEqual(await page.locator('#videoModelRail').count(), 0,
      '视频模式不能保留旧横向模型卡片栏');
    assert.strictEqual(await page.locator('.agent-select-control.video-mode-hidden').count(), 0,
      '视频模式不能隐藏原 AI 模型控件');
    assert.strictEqual(await page.locator('#agentSelectOptions [data-video-provider-id]').count(), 5,
      '供应商下拉框应包含 Google、火山、Grok、即梦四项');

    await page.evaluate(() => selectVideoProvider('suixiang-grok-text'));
    let state = await page.evaluate(() => ({
      provider: document.querySelector('#agentSelectWrapper .gf-select-text')?.textContent.trim(),
      model: document.getElementById('agentSelect')?.value,
      max: document.getElementById('videoModelLevel')?.max,
      disabled: document.getElementById('videoModelLevel')?.disabled,
      width: Math.round(document.getElementById('videoModelLevelControl')?.getBoundingClientRect().width || 0)
    }));
    assert.deepStrictEqual(state, {
      provider: 'Grok 文生视频',
      model: 'grok-imagine-video',
      max: '0',
      disabled: true,
      width: 0
    });
    const desktopLayout = await page.evaluate(() => {
      const selector = document.querySelector('.agent-select-control').getBoundingClientRect();
      const toolbar = document.querySelector('.input-toolbar').getBoundingClientRect();
      return {
        selector: { left: selector.left, right: selector.right, width: selector.width },
        toolbar: { left: toolbar.left, right: toolbar.right, width: toolbar.width }
      };
    });
    assert.ok(desktopLayout.selector.left >= desktopLayout.toolbar.left &&
      desktopLayout.selector.right <= desktopLayout.toolbar.right,
      `桌面视频模型控件不能超出输入工具栏: ${JSON.stringify(desktopLayout)}`);
    const activeModeVisible = await page.evaluate(() => {
      const active = document.getElementById('btn-video').getBoundingClientRect();
      const selector = document.querySelector('.mode-selector').getBoundingClientRect();
      const hit = document.elementFromPoint(active.left + active.width / 2, active.top + active.height / 2);
      return {
        contained: active.left >= selector.left && active.right <= selector.right,
        hittable: hit?.closest?.('#btn-video')?.id === 'btn-video'
      };
    });
    assert.deepStrictEqual(activeModeVisible, { contained: true, hittable: true },
      '当前视频模式按钮应保持可见且不能被模型控件遮挡');

    await page.evaluate(() => selectVideoProvider('suixiang-grok-image'));
    await page.locator('#videoModelLevel').evaluate(level => {
      level.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
      level.value = '1';
      level.dispatchEvent(new Event('input', { bubbles: true }));
    });
    assert.strictEqual(await page.locator('#agentSelect').inputValue(), 'grok-imagine-video-1.5');
    assert.strictEqual(await page.locator('#videoModelHint').textContent(), 'Grok 图生视频');
    assert.strictEqual(await page.locator('#videoModelHint').evaluate(el => el.classList.contains('is-visible')), true,
      '拖动时应显示当前模型名称');
    await page.locator('#videoModelLevel').dispatchEvent('pointerup');
    assert.strictEqual(await page.locator('#videoModelHint').evaluate(el => el.classList.contains('is-visible')), false,
      '松手后应隐藏模型名称浮层');

    assert.strictEqual(await page.locator('#videoModelLevelControl').isHidden(), true,
      'Grok 图生视频是独立选项，不应显示模型等级滑条');

    await page.evaluate(() => selectVideoProvider('suixiang-jimeng'));
    await page.locator('#videoModelLevel').evaluate(level => {
      level.value = '1';
      level.dispatchEvent(new Event('input', { bubbles: true }));
      level.dispatchEvent(new Event('change', { bubbles: true }));
    });
    assert.strictEqual(await page.locator('#agentSelect').inputValue(), 'video-ds-2.0');

    for (const providerId of ['google', 'seedance']) {
      await page.evaluate(id => selectVideoProvider(id), providerId);
      state = await page.evaluate(() => ({
        max: document.getElementById('videoModelLevel')?.max,
        disabled: document.getElementById('videoModelLevel')?.disabled
      }));
      assert.deepStrictEqual(state, { max: '0', disabled: true }, `${providerId} 单模型供应商应禁用滑条`);
    }

    submittedBody = '';
    await page.evaluate(() => {
      selectVideoProvider('suixiang-grok-text');
      droppedFiles = [new File([new Uint8Array([137, 80, 78, 71])], 'input.png', { type: 'image/png' })];
      document.getElementById('promptInput').value = 'text video must reject image';
    });
    await page.evaluate(() => executeTask());
    assert.strictEqual(submittedBody, '', 'Grok 文生视频携带参考图时不应提交请求');

    await page.evaluate(() => {
      selectVideoProvider('suixiang-grok-image');
      droppedFiles = [];
      document.getElementById('promptInput').value = 'image video must require image';
    });
    await page.evaluate(() => executeTask());
    assert.strictEqual(submittedBody, '', 'Grok 图生视频没有参考图时不应提交请求');

    await page.evaluate(() => {
      selectVideoProvider('suixiang-grok-image');
      const aspectSelect = document.getElementById('videoAspectRatio').closest('.gf-select');
      const portraitOption = aspectSelect.querySelector('.gf-option[data-value="3:4"]');
      selectOption(portraitOption, { stopPropagation() {} });
      droppedFiles = [new File([new Uint8Array([137, 80, 78, 71])], 'input.png', { type: 'image/png' })];
      document.getElementById('promptInput').value = '产品缓慢旋转展示';
    });
    const finalModeVisibility = await page.evaluate(() => {
      const active = document.getElementById('btn-video').getBoundingClientRect();
      const selector = document.querySelector('.mode-selector').getBoundingClientRect();
      const hit = document.elementFromPoint(active.left + active.width / 2, active.top + active.height / 2);
      return {
        text: document.getElementById('btn-video').textContent.trim(),
        active: document.getElementById('btn-video').classList.contains('active'),
        contained: active.left >= selector.left && active.right <= selector.right,
        hittable: hit?.closest?.('#btn-video')?.id === 'btn-video',
        scrollLeft: document.querySelector('.mode-selector').scrollLeft
      };
    });
    assert.strictEqual(finalModeVisibility.text, '视频模式');
    assert.strictEqual(finalModeVisibility.active, true);
    assert.strictEqual(finalModeVisibility.contained, true);
    assert.strictEqual(finalModeVisibility.hittable, true,
      `截图前视频模式按钮必须可见: ${JSON.stringify(finalModeVisibility)}`);
    await page.screenshot({
      path: path.join(__dirname, '..', 'target', 'video-model-selector-desktop.png'),
      fullPage: true
    });
    await page.locator('.input-toolbar').screenshot({
      path: path.join(__dirname, '..', 'target', 'video-model-selector-toolbar.png')
    });
    await page.evaluate(() => executeTask());
    assert.match(submittedBody, /grok-imagine-video-1\.5/, '提交请求应携带滑条当前模型');
    assert.match(submittedBody, /name="aspectRatio"\r?\n\r?\n3:4\r?\n/,
      '提交请求应携带用户选择的视频比例');

    const unlocked = await page.evaluate(() => ({
      disabled: document.getElementById('generateBtn').disabled,
      submitting: window.__taskSubmitting
    }));
    assert.strictEqual(unlocked.disabled, false, '提交失败后发送按钮必须恢复');
    assert.strictEqual(unlocked.submitting, false, '提交失败后不能残留提交锁');

    await page.evaluate(() => setMode('custom'));
    await page.waitForFunction(() => document.getElementById('videoModelLevelControl')?.hidden === true);
    assert.strictEqual(await page.locator('#videoModelLevelControl').isHidden(), true,
      '离开视频模式后短滑条必须隐藏');

    await page.setViewportSize({ width: 390, height: 844 });
    await page.evaluate(() => setMode('video'));
    await page.evaluate(() => selectVideoProvider('suixiang-jimeng'));
    await page.waitForSelector('#videoModelLevelControl:not([hidden])');
    const mobileLayout = await page.evaluate(() => ({
      controlWidth: Math.round(document.getElementById('videoModelLevelControl').getBoundingClientRect().width),
      pageOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      selectorRect: (() => {
        const rect = document.querySelector('.agent-select-control').getBoundingClientRect();
        return { left: rect.left, right: rect.right, width: rect.width, top: rect.top };
      })(),
      toolbarRect: (() => {
        const rect = document.querySelector('.input-toolbar').getBoundingClientRect();
        return { left: rect.left, right: rect.right, width: rect.width, top: rect.top };
      })(),
      viewportWidth: document.documentElement.clientWidth
    }));
    assert.strictEqual(mobileLayout.controlWidth, 82, '移动端短滑条仍应保持 82px');
    assert.strictEqual(mobileLayout.pageOverflow, false, '紧凑模型选择不能造成页面横向溢出');
    assert.ok(mobileLayout.selectorRect.left >= mobileLayout.toolbarRect.left &&
      mobileLayout.selectorRect.right <= mobileLayout.toolbarRect.right,
      `AI 模型控件不能超出输入工具栏: ${JSON.stringify(mobileLayout)}`);
    await page.locator('.agent-select-control').screenshot({
      path: path.join(__dirname, '..', 'target', 'video-model-selector-mobile.png')
    });

    assert.deepStrictEqual(pageErrors, [], `页面出现脚本错误: ${pageErrors.join('; ')}`);
    console.log('compact video model selector check passed');
  } finally {
    await browser.close();
  }
}

run().catch(error => {
  console.error(error.message);
  process.exit(1);
});
