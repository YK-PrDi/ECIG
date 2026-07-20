const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const root = path.resolve(__dirname, '..');

function testIndexHtmlWiresExternalModules() {
  const html = fs.readFileSync(path.join(root, 'frontend/index.html'), 'utf8');
  assert.match(html, /<script src="js\/api-client\.js"><\/script>/);
  assert.match(html, /<script src="js\/auth-session\.js"><\/script>/);
  assert.match(html, /<script src="js\/task-polling\.js\?v=20260718-video-concurrency"><\/script>/);
  assert.match(html, /<script src="js\/generation-controls\.js\?v=20260718-video-concurrency"><\/script>/);
  assert.match(html, /<script src="js\/canvas-selection\.js"><\/script>/);
  assert.match(html, /<script src="js\/custom-direct-prompt\.js"><\/script>/);
  const autoBoardSection = html.slice(html.indexOf('function autoAddToBoard'), html.indexOf('function appendThoughts'));
  assert.match(autoBoardSection, /r\.type === 'video'/, '视频结果不应被图片画布节点接收');
  assert.match(html, /beginGenerationTask\(data\.taskId\)/);
  const executeTaskSection = html.slice(html.indexOf('async function executeTask'), html.indexOf('async function __executeTaskInner'));
  assert.doesNotMatch(executeTaskSection, /stopActiveTask|generationControls\.isActive/,
    '主生成按钮不能再承担停止已有任务的职责');
  const progressCardSection = html.slice(html.indexOf('function insertProgressCard'), html.indexOf('async function optimizeVideoPrompt'));
  assert.match(progressCardSection, /progress-card-progress-row[\s\S]*progress-card-bar-wrap[\s\S]*progress-stop-btn/,
    '单任务停止按钮应位于对应进度条右侧');
  const stopGenerationSection = html.slice(html.indexOf('async function stopGeneration'), html.indexOf('function updateProgressCard'));
  assert.match(stopGenerationSection, /generationControls\.stopTask\(taskId\)/);
  assert.match(stopGenerationSection, /正在中断当前调用/,
    '停止请求应明确表示正在中断当前供应商调用');
  assert.doesNotMatch(stopGenerationSection, /stopPollingTask\(taskId\)|card\.remove\(\)/,
    '停止请求后必须继续轮询到后端进入终态');
  const reanalyzeSection = html.slice(html.indexOf('async function reanalyzeCard'), html.indexOf('function isTextOverlayKey'));
  assert.match(reanalyzeSection, /analyzeWithGemini\(files, doubaoUserPrompt, 1, withText\)/,
    '重分析应带全部参考图走单元整合链路');
  assert.ok(!/const singleFile =/.test(reanalyzeSection), '重分析不能退化为只带一张参考图');
  const customSubmitSection = html.slice(
    html.indexOf("} else if (currentMode === 'custom')"),
    html.indexOf("} else if (currentMode === 'ecommerce')")
  );
  assert.match(customSubmitSection, /const customStyleInfo = getCustomStyleInfo\(\)/,
    '跳过 AI 分析时仍需取得当前风格信息，不能引用未定义变量');
  assert.match(customSubmitSection, /const skipAnalysis = document\.getElementById\('skipGeminiToggle'\)\?\.checked === true/,
    '自定义提交应使用本次点击的跳过分析状态');
  assert.doesNotMatch(customSubmitSection, /_doubaoConfirmed\s*=\s*true/,
    '跳过 AI 分析不能污染后续请求的分析确认状态');
  assert.match(customSubmitSection, /escHtml\(directPrompt\.prompt\)/,
    '跳过 AI 分析时展示内容应复用实际提交的直接生成 Prompt');
  assert.doesNotMatch(customSubmitSection, /escHtml\(finalPrompt\)/,
    '自定义直接生成分支不能引用已删除的 finalPrompt');
  assert.match(html, /AiStudioAuth\.configure\(/);
  assert.match(html, /AiStudioAuth\.checkSession\(\)/);
  assert.doesNotMatch(html, /id="videoModelRail"/, '视频配置不能再增加横向模型卡片栏');
  assert.match(html, /fetch\('\/api\/video\/models'\)/, '视频模型应从后端目录动态加载');
  assert.match(html, /id="videoModelLevel"/, '原模型下拉框下方应包含短模型等级滑条');
  assert.match(html, /id="videoModelHint"/, '短滑条拖动时应显示当前模型浮层');
  assert.match(html, /function renderVideoProviderOptions\(\)/, '视频模型应按供应商渲染原下拉框');
  assert.match(html, /function selectVideoProvider\(providerId\)/, '视频模式应支持供应商切换');
  assert.match(html, /function setVideoModelLevel\(level/, '滑条应同步供应商内部模型等级');
  assert.match(html, /agentSelect\.value = selected\.id/, '视频模型选择结果应同步到隐藏 agentSelect');
  assert.doesNotMatch(html, /agentSelectControl\.classList\.toggle\('video-mode-hidden', mode === 'video'\)/,
    '视频模式必须复用原 AI 模型位置，不能隐藏该控件');
  assert.match(html, /VIDEO_MODEL_FALLBACKS[\s\S]*grok-imagine-video-1\.5[\s\S]*video-ds-2\.0/,
    '视频模型接口失败时仍应保留六模型回退目录');
  assert.ok(!/\(async function checkSession\(\)/.test(html), 'index.html should not keep inline checkSession implementation');
  assert.ok(!/__pollTimers/.test(html), 'index.html should not keep task polling state');
  assert.ok(!/function\s+fetchTaskStatus\s*\(/.test(html), 'index.html should not keep inline task status fetcher');
  assert.ok(!/function\s+startPolling\s*\(taskId\)/.test(html), 'index.html should not keep inline task polling loop');
}

function testCustomDirectPrompt() {
  const sandbox = loadBrowserScript('frontend/js/custom-direct-prompt.js');
  const directPrompt = sandbox.AiStudioCustomDirectPrompt;
  assert.ok(directPrompt, 'AiStudioCustomDirectPrompt should be exported');

  const whiteBackground = directPrompt.build({
    userPrompt: '请生成白底图，保持产品原样',
    styleKey: 'original',
    stylePrompt: 'DEFAULT_STYLE_MARKETING_SCENE',
    categoryPrompt: 'CATEGORY_SELLING_POINT_PROMPT',
    skipAnalysis: true,
    withText: false
  });

  assert.strictEqual(whiteBackground.isWhiteBackgroundDirect, true);
  assert.match(whiteBackground.prompt, /唯一产品主体/);
  assert.match(whiteBackground.prompt, /外形、比例、颜色、材质、结构/);
  assert.match(whiteBackground.prompt, /纯白无缝背景/);
  assert.match(whiteBackground.prompt, /自然.*接触阴影/);
  assert.match(whiteBackground.prompt, /无场景、无道具/);
  assert.match(whiteBackground.prompt, /不要出现任何文字/);
  assert.doesNotMatch(whiteBackground.prompt, /DEFAULT_STYLE_MARKETING_SCENE/);
  assert.doesNotMatch(whiteBackground.prompt, /CATEGORY_SELLING_POINT_PROMPT/);

  const styled = directPrompt.build({
    userPrompt: '请生成白底图',
    styleKey: 'tech-blue',
    stylePrompt: 'TECH_BLUE_STYLE',
    categoryPrompt: 'CATEGORY_PROMPT',
    skipAnalysis: true,
    withText: false
  });
  assert.strictEqual(styled.isWhiteBackgroundDirect, false);
  assert.strictEqual(styled.prompt, '请生成白底图\nTECH_BLUE_STYLE\nCATEGORY_PROMPT');

  const withText = directPrompt.build({
    userPrompt: '请生成纯白背景商品图',
    styleKey: 'original',
    stylePrompt: 'ORIGINAL_STYLE',
    categoryPrompt: 'CATEGORY_PROMPT',
    skipAnalysis: true,
    withText: true
  });
  assert.strictEqual(withText.isWhiteBackgroundDirect, false);
  assert.strictEqual(withText.prompt, '请生成纯白背景商品图\nORIGINAL_STYLE\nCATEGORY_PROMPT');
}

function loadBrowserScript(file, sandboxOverrides = {}) {
  const sandbox = {
    console,
    setInterval: () => 101,
    clearInterval: () => {},
    setTimeout: (fn) => {
      fn();
      return 202;
    },
    clearTimeout: () => {},
    document: {
      visibilityState: 'visible',
      addEventListener: () => {},
      getElementById: () => null,
      querySelector: () => null
    },
    alert: () => {},
    ...sandboxOverrides
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  vm.runInContext(source, sandbox, { filename: file });
  return sandbox;
}

function testCanvasSelection() {
  const sandbox = loadBrowserScript('frontend/js/canvas-selection.js');
  const selection = sandbox.AiStudioCanvasSelection;
  assert.ok(selection, '画布选择工具应导出 AiStudioCanvasSelection');

  const nodes = [
    { id: 'left', x: 10, y: 10, w: 100, h: 100, src: '/api/image?path=left.png', savePath: 'left.png' },
    { id: 'right', x: 180, y: 40, w: 120, h: 80, src: '/api/image?path=right.png', savePath: 'right.png' },
    { id: 'outside', x: 380, y: 40, w: 100, h: 100, src: '/api/image?path=outside.png' }
  ];

  assert.deepStrictEqual(
    Array.from(selection.selectionIds(nodes, { x: 80, y: 60, w: 140, h: 80 }, new Set(['outside']), false)),
    ['left', 'right'],
    '普通框选应命中相交节点并替换旧选择'
  );
  assert.deepStrictEqual(
    Array.from(selection.selectionIds(nodes, { x: 80, y: 60, w: 140, h: 80 }, new Set(['outside']), true)).sort(),
    ['left', 'outside', 'right'],
    'Ctrl/Cmd 框选应追加命中节点'
  );

  const moved = selection.groupMove(nodes, new Set(['left', 'right']), 25, -10);
  assert.deepStrictEqual(
    moved.map(node => ({ id: node.id, x: node.x, y: node.y })),
    [
      { id: 'left', x: 35, y: 0 },
      { id: 'right', x: 205, y: 30 },
      { id: 'outside', x: 380, y: 40 }
    ],
    '组移动只能改变选中节点的位置'
  );

  assert.deepStrictEqual(
    selection.batchTargets(nodes, new Set(['left', 'right', 'outside'])).map(node => node.id),
    ['left', 'right'],
    '批量保存仅返回具有可保存来源的选中节点'
  );
}

function response({ status = 200, contentType = 'application/json', body = '{}' } = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get(name) {
        return String(name).toLowerCase() === 'content-type' ? contentType : '';
      }
    },
    async text() {
      return body;
    },
    async json() {
      return JSON.parse(body);
    }
  };
}

async function testApiClient() {
  const sandbox = loadBrowserScript('frontend/js/api-client.js');
  assert.ok(sandbox.AiStudioApi, 'AiStudioApi should be exported');

  const parsed = await sandbox.AiStudioApi.readJson(response({ body: '{"ok":true}' }), '测试接口');
  assert.strictEqual(JSON.stringify(parsed), JSON.stringify({ ok: true }));

  await assert.rejects(
    () => sandbox.AiStudioApi.readJson(response({
      contentType: 'text/html',
      body: '<!doctype html>'
    }), '登录接口'),
    /不是 JSON|not JSON|HTML|代理/
  );
}

async function testAuthSession() {
  let overlayVisible = false;
  let updatedUser = null;
  let initCount = 0;
  const fetchCalls = [];
  const sandbox = loadBrowserScript('frontend/js/api-client.js', {
    fetch: async (url, options) => {
      fetchCalls.push({ url, options });
      if (url === '/api/auth/check') {
        return response({ body: '{"authenticated":true,"user":{"id":7,"username":"u7"}}' });
      }
      if (url === '/api/auth/heartbeat') {
        return response({ body: '{"authenticated":true,"user":{"id":7,"username":"u7"},"serverTime":1}' });
      }
      return response({ status: 404, body: '{"error":"missing"}' });
    },
    document: {
      visibilityState: 'visible',
      addEventListener: () => {},
      getElementById: (id) => {
        if (id === 'loginOverlay') {
          return { style: { display: overlayVisible ? 'flex' : 'none' } };
        }
        return null;
      }
    }
  });
  vm.runInContext(
    fs.readFileSync(path.join(root, 'frontend/js/auth-session.js'), 'utf8'),
    sandbox,
    { filename: 'frontend/js/auth-session.js' }
  );

  sandbox.AiStudioAuth.configure({
    updateCurrentUser: (user) => { updatedUser = user; },
    initApp: () => { initCount += 1; },
    setLoginOverlayVisible: (visible) => { overlayVisible = visible; }
  });

  await sandbox.AiStudioAuth.checkSession();
  assert.strictEqual(overlayVisible, false);
  assert.strictEqual(JSON.stringify(updatedUser), JSON.stringify({ id: 7, username: 'u7' }));
  assert.strictEqual(initCount, 1);
  assert.ok(fetchCalls.some(call => call.url === '/api/auth/heartbeat'), 'checkSession should start heartbeat');
}

async function testTaskPolling() {
  const cleared = [];
  const sandbox = loadBrowserScript('frontend/js/api-client.js', {
    fetch: async () => response({ status: 401, body: '{"success":false}' }),
    clearInterval: (id) => cleared.push(id)
  });
  vm.runInContext(
    fs.readFileSync(path.join(root, 'frontend/js/task-polling.js'), 'utf8'),
    sandbox,
    { filename: 'frontend/js/task-polling.js' }
  );

  const polling = sandbox.AiStudioTaskPolling.create({
    timers: new Map([['t1', { pollTimer: 11, elapsedTimer: 12 }]])
  });
  polling.stop('t1');
  assert.deepStrictEqual(cleared, [11, 12]);

  await assert.rejects(
    () => polling.fetchStatus('expired-task'),
    (error) => error && error.sessionExpired === true
  );

  let finalizeCount = 0;
  let stoppingCurrentText = '';
  const stoppingSandbox = loadBrowserScript('frontend/js/api-client.js', {
    fetch: async () => response({ body: '{"status":"stopping","progress":0,"total":1,"results":[]}' })
  });
  vm.runInContext(
    fs.readFileSync(path.join(root, 'frontend/js/task-polling.js'), 'utf8'),
    stoppingSandbox,
    { filename: 'frontend/js/task-polling.js' }
  );
  const stoppingPolling = stoppingSandbox.AiStudioTaskPolling.create({
    timers: new Map([['t2', { seenCount: 0 }]]),
    updateProgressCard: (taskId, pct, countText, etaText, currentText) => {
      stoppingCurrentText = currentText;
    },
    finalizeProgressCard: () => { finalizeCount += 1; },
    renderGeneratedImages: () => {},
    autoAddToBoard: () => {}
  });
  await stoppingPolling.pollOnce('t2', Date.now());
  assert.strictEqual(finalizeCount, 0, 'stopping task must keep polling until it reaches a terminal state');
  assert.match(stoppingCurrentText, /正在中断当前调用/,
    'stopping 状态不应继续提示等待供应商自然返回');
}

async function testGenerationControls() {
  const calls = [];
  const sandbox = loadBrowserScript('frontend/js/api-client.js', {
    fetch: async (url, options) => {
      calls.push({ url, options });
      return response({ body: '{"success":true,"status":"stopping"}' });
    }
  });
  vm.runInContext(
    fs.readFileSync(path.join(root, 'frontend/js/generation-controls.js'), 'utf8'),
    sandbox,
    { filename: 'frontend/js/generation-controls.js' }
  );

  const controls = sandbox.AiStudioGenerationControls.create();
  controls.activate('task-1');
  controls.activate('task-2');
  assert.strictEqual(controls.isActive(), true);
  assert.strictEqual(controls.isActive('task-1'), true);
  assert.strictEqual(controls.isActive('task-2'), true);
  assert.deepStrictEqual(Array.from(controls.activeTaskIds()), ['task-1', 'task-2']);

  await Promise.all([controls.stopTask('task-1'), controls.stopTask('task-1')]);

  assert.strictEqual(calls.length, 1, 'rapid repeated clicks must issue only one stop request');
  assert.strictEqual(calls[0].url, '/api/task/task-1/stop');
  assert.strictEqual(controls.isStopping('task-1'), true);
  assert.strictEqual(controls.isStopping('task-2'), false);
  controls.complete('task-1');
  assert.strictEqual(controls.isActive(), true);
  assert.strictEqual(controls.isActive('task-1'), false);
  assert.strictEqual(controls.isActive('task-2'), true);
  controls.complete('task-2');
  assert.strictEqual(controls.isActive(), false);
}

(async () => {
  testIndexHtmlWiresExternalModules();
  testCustomDirectPrompt();
  testCanvasSelection();
  await testApiClient();
  await testAuthSession();
  await testTaskPolling();
  await testGenerationControls();
  console.log('frontend session modules tests passed');
})().catch(error => {
  console.error(error);
  process.exit(1);
});
