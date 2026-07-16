const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const root = path.resolve(__dirname, '..');

function testIndexHtmlWiresExternalModules() {
  const html = fs.readFileSync(path.join(root, 'frontend/index.html'), 'utf8');
  assert.match(html, /<script src="js\/api-client\.js"><\/script>/);
  assert.match(html, /<script src="js\/auth-session\.js"><\/script>/);
  assert.match(html, /<script src="js\/task-polling\.js"><\/script>/);
  assert.match(html, /<script src="js\/generation-controls\.js"><\/script>/);
  assert.match(html, /<script src="js\/canvas-selection\.js"><\/script>/);
  assert.match(html, /<script src="js\/workbench-layout\.js"><\/script>/);
  assert.match(html, /<script src="js\/inpaint-session\.js"><\/script>/);
  assert.match(html, /<script src="js\/kaipin-material-selection\.js"><\/script>/);
  assert.match(html, /class="generation-toolbar"/);
  assert.match(html, /id="rectMaskBtn"/);
  assert.match(html, /id="undoMaskBtn"/);
  assert.match(html, /id="redoMaskBtn"/);
  assert.match(html, /id="kpMaterialSelectionSummary"/);
  const autoBoardSection = html.slice(html.indexOf('function autoAddToBoard'), html.indexOf('function appendThoughts'));
  assert.match(autoBoardSection, /r\.type === 'video'/, '视频结果不应被图片画布节点接收');
  assert.match(html, /generationControls\.isActive\(\)/);
  assert.match(html, /beginGenerationTask\(data\.taskId\)/);
  const reanalyzeSection = html.slice(html.indexOf('async function reanalyzeCard'), html.indexOf('function isTextOverlayKey'));
  assert.match(reanalyzeSection, /analyzeWithGemini\(files, doubaoUserPrompt, 1, withText\)/,
    '重分析应带全部参考图走单元整合链路');
  assert.ok(!/const singleFile =/.test(reanalyzeSection), '重分析不能退化为只带一张参考图');
  assert.match(html, /AiStudioAuth\.configure\(/);
  assert.match(html, /AiStudioAuth\.checkSession\(\)/);
  assert.ok(!/\(async function checkSession\(\)/.test(html), 'index.html should not keep inline checkSession implementation');
  assert.ok(!/__pollTimers/.test(html), 'index.html should not keep task polling state');
  assert.ok(!/function\s+fetchTaskStatus\s*\(/.test(html), 'index.html should not keep inline task status fetcher');
  assert.ok(!/function\s+startPolling\s*\(taskId\)/.test(html), 'index.html should not keep inline task polling loop');
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

function testWorkbenchLayout() {
  const sandbox = loadBrowserScript('frontend/js/workbench-layout.js');
  const layout = sandbox.AiStudioWorkbenchLayout;
  assert.ok(layout, '工作台布局工具应导出 AiStudioWorkbenchLayout');
  assert.strictEqual(layout.clampPanelWidth(80, 160, 420), 160);
  assert.strictEqual(layout.clampPanelWidth(520, 160, 420), 420);
  assert.deepStrictEqual(
    JSON.parse(JSON.stringify(layout.preserveCameraCenter(
      { x: 10, y: 20 }, 2, { width: 800, height: 600 }, { width: 1000, height: 700 }
    ))),
    { x: 110, y: 70 }
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
    updateProgressCard: () => {},
    finalizeProgressCard: () => { finalizeCount += 1; },
    renderGeneratedImages: () => {},
    autoAddToBoard: () => {}
  });
  await stoppingPolling.pollOnce('t2', Date.now());
  assert.strictEqual(finalizeCount, 0, 'stopping task must keep polling until it reaches a terminal state');
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
  assert.strictEqual(controls.isActive(), true);

  await Promise.all([controls.stopActiveTask(), controls.stopActiveTask()]);

  assert.strictEqual(calls.length, 1, 'rapid repeated clicks must issue only one stop request');
  assert.strictEqual(calls[0].url, '/api/task/task-1/stop');
  assert.strictEqual(controls.isStopping(), true);
  controls.complete('task-1');
  assert.strictEqual(controls.isActive(), false);
}

(async () => {
  testWorkbenchLayout();
  testIndexHtmlWiresExternalModules();
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
