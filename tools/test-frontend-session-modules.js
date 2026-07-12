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
}

(async () => {
  testIndexHtmlWiresExternalModules();
  await testApiClient();
  await testAuthSession();
  await testTaskPolling();
  console.log('frontend session modules tests passed');
})().catch(error => {
  console.error(error);
  process.exit(1);
});
