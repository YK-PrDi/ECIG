/**
 * 电商模式视图 — 钉钉产品图批量生成
 * 接口：GET /api/products、POST /api/generate(JSON)、GET /api/task/{id} 轮询、
 *       POST /api/task/{id}/stop、GET /api/gallery（文件夹结果展开）
 */
(function () {
  const App = window.App;
  const $ = App.$, esc = App.escapeHtml;

  const state = {
    products: [],
    running: false,
    stopPoll: null,
    taskId: null,
  };

  function init(view) {
    view.querySelector('#ecommerceBody').innerHTML = `
      <div class="split-pane">
        <section class="panel">
          <div class="card">
            <div class="card-head"><b>产品列表</b>
              <button class="link-btn" id="ecReload">↻ 刷新</button>
            </div>
            <div id="ecProductList" class="ec-product-list">
              <div class="pending-line">加载中…</div>
            </div>
          </div>
          <div class="card">
            <div class="card-head"><b>生成配置</b></div>
            <div class="form-row"><label>生图模型</label><select id="ecAgent"></select></div>
            <div class="form-row"><label>画面比例</label>
              <select id="ecAspect"><option>1:1</option><option>3:4</option><option>4:3</option><option>9:16</option><option>16:9</option></select>
            </div>
            <div class="form-row"><label>每品张数</label><input id="ecBatch" type="number" min="1" max="10" value="1"></div>
            <div class="form-row"><label>负面提示</label><input id="ecNeg" placeholder="低质量、变形、水印（可选）"></div>
            <textarea id="ecPrompt" class="goal-text" rows="3" placeholder="补充提示词（可选，留空自动生成）"></textarea>
            <button class="btn btn-primary btn-block" id="ecRun">开始生成</button>
          </div>
        </section>
        <section class="panel">
          <h2 class="panel-title">生成结果 <small class="panel-sub" id="ecMeta"></small></h2>
          <div id="ecFlow"></div>
          <div id="ecProgress"></div>
          <div id="ecResults" class="result-grid"></div>
          <div class="output-empty" id="ecEmpty">
            <div class="empty-ico">◉</div>
            <p>勾选左侧产品后点击「开始生成」</p>
            <p class="empty-sub">产品数据来自钉钉多维表格（<code>GET /api/products</code>）</p>
          </div>
        </section>
      </div>`;

    view.querySelector('#ecReload').addEventListener('click', loadProducts);
    view.querySelector('#ecRun').addEventListener('click', run);
    App.flowChain('#ecFlow', ['选择产品', '提交任务', '批量生成', '完成'], 0);
    fillAgents();
    document.addEventListener('agents:loaded', fillAgents);
    loadProducts();
  }

  function fillAgents() {
    const sel = $('#ecAgent');
    if (!sel) return;
    const current = sel.value;
    const agents = App.state.agents.length ? App.state.agents : [{ id: 'gpt-image', name: 'GPT-Image' }, { id: 'gemini', name: 'Gemini' }];
    sel.innerHTML = agents.map((a) =>
      `<option value="${esc(a.id)}">${esc(a.name || a.id)}</option>`).join('');
    const preferred = agents.find((a) => a.id === current) || agents.find((a) => String(a.id).startsWith('gpt-image'));
    if (preferred) sel.value = preferred.id;
  }

  async function loadProducts() {
    const box = $('#ecProductList');
    box.innerHTML = '<div class="pending-line">加载中…</div>';
    if (App.state.demoMode) {
      state.products = [
        { id: 'demo-1', name: '手持花洒 HS-201', category: '厨卫淋浴配件', main_count: 3, sku_count: 2 },
        { id: 'demo-2', name: '卫浴置物架 ZW-88', category: '卫浴收纳', main_count: 4, sku_count: 3 },
        { id: 'demo-3', name: '厨房锅盖架 GG-12', category: '厨房收纳', main_count: 2, sku_count: 1 },
      ];
    } else {
      try {
        const data = await window.YurenApi.listProducts();
        if (data.error) throw new Error(data.error);
        state.products = data.products || [];
      } catch (e) {
        box.innerHTML = `<div class="pending-line error">产品加载失败：${esc(e.message)}<br><small>请先在「企业设置」配置钉钉多维表格</small></div>`;
        return;
      }
    }
    if (!state.products.length) {
      box.innerHTML = '<div class="pending-line">暂无产品数据</div>';
      return;
    }
    box.innerHTML = state.products.map((p) => `
      <label class="ec-product">
        <input type="checkbox" value="${esc(p.id)}">
        <div class="ecp-info">
          <div class="ecp-name">${esc(p.name)}</div>
          <div class="ecp-sub">${esc(p.category || '未分类')} · 主图 ${p.main_count ?? 0} · SKU ${p.sku_count ?? 0}</div>
        </div>
      </label>`).join('');
  }

  async function run() {
    if (state.running) return;
    const ids = Array.from(document.querySelectorAll('#ecProductList input:checked')).map((c) => c.value);
    if (!ids.length) { App.toast('请先勾选要生成的产品'); return; }

    const body = {
      productIds: ids,
      agentId: $('#ecAgent').value,
      prompt: $('#ecPrompt').value.trim() || '自动生成',
      sessionId: App.state.sessionId,
      aspect: $('#ecAspect').value,
      negativePrompt: $('#ecNeg').value.trim(),
      batchCount: Number($('#ecBatch').value) || 1,
      gptSize: '1024x1024',
      gptQuality: 'standard',
    };

    state.running = true;
    App.flowChain('#ecFlow', ['选择产品', '提交任务', '批量生成', '完成'], 1);
    const btn = $('#ecRun');
    btn.disabled = true;
    btn.classList.add('loading');
    btn.textContent = '提交中…';
    $('#ecEmpty').classList.add('hidden');
    $('#ecResults').innerHTML = '';
    showProgress(0, 1, '任务提交中…');

    try {
      let taskId;
      if (App.state.demoMode) {
        await new Promise((r) => setTimeout(r, 800));
        taskId = 'demo-task';
        runDemoTask(ids);
      } else {
        const data = await window.YurenApi.generate(body);
        if (data.error || !data.taskId) throw new Error(data.error || '任务创建失败');
        taskId = data.taskId;
        state.taskId = taskId;
        state.stopPoll = App.pollTask(taskId, {
          onProgress: (d) => showProgress(d.progress || 0, d.total || 1, d.currentProduct, d.status),
          onResult: renderResult,
          onDone: (d) => finishTask(d),
        });
      }
      btn.textContent = '生成中…';
      App.flowChain('#ecFlow', ['选择产品', '提交任务', '批量生成', '完成'], 2);
      $('#ecMeta').textContent = `（任务 ${taskId.slice(0, 8)}…）`;
    } catch (e) {
      App.toast('生成失败：' + e.message);
      resetRun();
    }
  }

  function showProgress(done, total, current, status) {
    const pct = total ? Math.round((done / total) * 100) : 0;
    $('#ecProgress').innerHTML = `
      <div class="task-progress">
        <b>${status === 'stopping' ? '正在停止…' : '批量生成中'}</b>
        <div class="tp-bar"><i style="width:${pct}%"></i></div>
        <div class="tp-meta">
          <span>${current ? '正在处理：' + esc(current) : '等待任务调度'}</span>
          <span>${done}/${total}（${pct}%）</span>
        </div>
        <button class="btn btn-ghost" id="ecStop" style="margin-top:8px">停止任务</button>
      </div>`;
    const stopBtn = $('#ecStop');
    if (stopBtn) stopBtn.addEventListener('click', async () => {
      try {
        if (state.taskId && !App.state.demoMode) await window.YurenApi.stopTask(state.taskId);
        App.toast('已发送停止指令');
      } catch (e) { App.toast('停止失败：' + e.message); }
    });
  }

  async function renderResult(result) {
    const out = result.output || '';
    if (/\.(jpe?g|png|webp|gif)$/i.test(out) || /^https?:\/\//i.test(out)) {
      const raw = result.localPath || out;
      addImage(App.toImgUrl(raw), result.name, raw);
    } else if (out) {
      // output 是文件夹：经 /api/gallery 展开
      try {
        const g = await window.YurenApi.gallery(out);
        (g.items || []).filter((it) => it.type === 'image')
          .forEach((it) => addImage(App.toImgUrl(it.path), `${result.name} · ${it.name}`, it.path));
      } catch { /* 忽略单批次展开失败 */ }
    }
  }

  function addImage(url, name, raw) {
    const publishBtn = raw
      ? `<button class="ri-btn" data-publish-asset="${esc(raw)}" data-title="${esc(name || '')}" data-type="image" data-mode="ecommerce">☁ 存入企业库</button>`
      : '';
    $('#ecResults').insertAdjacentHTML('beforeend', `
      <div class="result-item">
        <img src="${esc(url)}" alt="${esc(name || '生成图')}" loading="lazy">
        <div class="ri-meta"><span>${esc(name || '')}</span><a href="${esc(url)}" target="_blank">查看</a></div>
        ${publishBtn ? `<div class="ri-actions">${publishBtn}</div>` : ''}
      </div>`);
  }

  function finishTask(data) {
    const ok = (data.results || []).filter((r) => r.status === 'success' && !String(r.name).startsWith('__')).length;
    const err = (data.results || []).filter((r) => r.status === 'error').length;
    App.flowChain('#ecFlow', ['选择产品', '提交任务', '批量生成', '完成'], 4);
    App.toast(`任务结束：成功 ${ok} 项${err ? '，失败 ' + err + ' 项' : ''}`);
    $('#ecProgress').innerHTML = '';
    resetRun();
  }

  function resetRun() {
    state.running = false;
    state.taskId = null;
    const btn = $('#ecRun');
    if (btn) { btn.disabled = false; btn.classList.remove('loading'); btn.textContent = '开始生成'; }
  }

  /** 演示模式：模拟任务进度与结果 */
  function runDemoTask(ids) {
    let done = 0;
    const total = ids.length;
    const timer = setInterval(() => {
      done++;
      showProgress(done, total, state.products.find((p) => p.id === ids[done - 1])?.name || '', 'running');
      if (done >= total) {
        clearInterval(timer);
        finishTask({ results: ids.map((id) => ({ name: id, status: 'success' })) });
        $('#ecMeta').textContent = '（演示模式：未真实生图）';
      }
    }, 1200);
  }

  App.registerView('ecommerce', { init });
})();
