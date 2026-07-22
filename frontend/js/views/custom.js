/**
 * 自定义模式视图 — 参考图风格迁移 + 局部重绘
 * 接口：POST /api/custom_analyze（可选分析）、POST /api/custom_generate、
 *       POST /api/inpaint（蒙版重绘）、GET /api/task/{id} 轮询
 */
(function () {
  const App = window.App;
  const $ = App.$, $$ = App.$$, esc = App.escapeHtml;

  const state = {
    images: [],        // File[]
    running: false,
    taskId: null,
    stopPoll: null,
    analysisText: '',  // custom_analyze 返回的文本（可编辑）
    catPath: [],       // 产品类目路径（EC_CATEGORY_TREE）
    sellings: new Set(), // 已选中的品类卖点 prompt
    inpaint: {         // 蒙版重绘状态
      image: null,     // File
      strokes: null,   // canvas 绘制状态
    },
  };

  function init(view) {
    view.querySelector('#customBody').innerHTML = `
      <div class="split-pane">
        <section class="panel">
          <div class="card">
            <div class="card-head"><b>参考图</b><small class="muted">（白底产品图，可多张）</small></div>
            <div class="upload-strip" id="cuUploadStrip">
              <div class="upload-box small" id="cuUpload">
                <span class="upload-ico">＋</span><small>上传图片</small>
                <input type="file" accept="image/*" multiple hidden>
              </div>
            </div>
          </div>
          <div class="card">
            <div class="card-head"><b>生成配置</b></div>
            <div class="form-row">
              <label>产品类目</label>
              <button class="cat-select" id="cuCatBtn" type="button">
                <span id="cuCatText">— 点击选择品类</span><span class="caret">▾</span>
              </button>
            </div>
            <div class="cat-panel hidden" id="cuCatPanel">
              <div class="cat-search-row">
                <input id="cuCatSearch" placeholder="🔎 搜索品类（如：花洒）">
                <button class="link-btn" id="cuCatClear" type="button">清除</button>
              </div>
              <div class="cat-cols" id="cuCatCols"></div>
            </div>
            <div id="cuCatSellings" class="hidden" style="margin-bottom:10px">
              <div class="hint-line" style="margin-bottom:6px">品类卖点（点击加入提示词）：</div>
              <div class="chip-group" id="cuCatSellingChips"></div>
            </div>
            <textarea id="cuPrompt" class="goal-text" rows="3"
              placeholder="描述想要的画面，如：白底商品图，专业摄影棚灯光…"></textarea>
            <div class="form-row"><label>生图模型</label><select id="cuAgent"></select></div>
            <div class="form-row"><label>画面比例</label>
              <select id="cuAspect"><option value="auto">自动</option><option>1:1</option><option>3:4</option><option>9:16</option><option>16:9</option></select>
            </div>
            <div class="form-row"><label>生成张数</label><input id="cuCount" type="number" min="1" max="10" value="2"></div>
            <div class="form-row">
              <label>LoRA</label>
              <div class="chip-group">
                <button class="chip" id="cuLora">启用电商展台 LoRA</button>
              </div>
            </div>
            <label class="check-line"><input type="checkbox" id="cuSkipAnalyze"> 跳过 Gemini 分析，直接生成</label>
            <label class="check-line"><input type="checkbox" id="cuNoText"> 图片上不要加文字</label>
            <button class="btn btn-primary btn-block" id="cuRun" style="margin-top:10px">开始生成</button>
          </div>
          <div class="card">
            <div class="card-head"><b>局部重绘</b><small class="muted">（inpaint）</small></div>
            <p class="hint-line">在参考图上加蒙版后，可用文字指令重绘局部区域。</p>
            <button class="btn btn-outline btn-block" id="cuInpaintOpen">打开蒙版编辑器</button>
          </div>
        </section>
        <section class="panel">
          <h2 class="panel-title">生成结果 <small class="panel-sub" id="cuMeta"></small></h2>
          <div id="cuFlow"></div>
          <div id="cuAnalysis" class="hidden">
            <div class="card">
              <div class="card-head"><b>Gemini 分析结果</b><small class="muted">（可编辑，确认后用于生成）</small></div>
              <textarea id="cuAnalysisText" class="goal-text" rows="8"></textarea>
              <button class="btn btn-primary btn-block" id="cuConfirmGenerate" style="margin-top:10px">确认并生成</button>
            </div>
          </div>
          <div id="cuProgress"></div>
          <div id="cuResults" class="result-grid"></div>
          <div class="output-empty" id="cuEmpty">
            <div class="empty-ico">◈</div>
            <p>上传参考图并填写提示词后，点击「开始生成」</p>
            <p class="empty-sub">接口：<code>POST /api/custom_generate</code></p>
          </div>
        </section>
      </div>

      <!-- 蒙版编辑器模态 -->
      <div class="mask-modal hidden" id="maskModal">
        <div class="mask-editor">
          <div class="card-head"><b>蒙版编辑器</b><small class="muted">涂抹要重绘的区域</small>
            <button class="link-btn" id="maskClose">关闭</button>
          </div>
          <div class="mask-stage" id="maskStage">
            <canvas id="maskCanvas"></canvas>
          </div>
          <div class="mask-tools">
            <label>笔刷 <input type="range" id="maskBrush" min="10" max="80" value="36"></label>
            <button class="btn btn-ghost" id="maskClear">清除蒙版</button>
            <input id="maskPrompt" class="spec-input" placeholder="重绘指令，如：把背景换成木纹桌面">
            <button class="btn btn-primary" id="maskSubmit">开始重绘</button>
          </div>
        </div>
      </div>`;

    bindUpload(view);
    fillAgents();
    document.addEventListener('agents:loaded', fillAgents);
    App.flowChain('#cuFlow', ['上传与配置', '分析确认', '生成中', '完成'], 0);
    initCategoryTree(view);
    view.querySelector('#cuLora').addEventListener('click', (e) => e.target.classList.toggle('on'));
    view.querySelector('#cuRun').addEventListener('click', run);
    view.querySelector('#cuInpaintOpen').addEventListener('click', openMaskEditor);
    view.querySelector('#maskClose').addEventListener('click', () => $('#maskModal').classList.add('hidden'));
    view.querySelector('#maskClear').addEventListener('click', clearMask);
    view.querySelector('#maskSubmit').addEventListener('click', submitInpaint);
  }

  function fillAgents() {
    const sel = $('#cuAgent');
    if (!sel) return;
    const current = sel.value; // 保留当前选择
    const agents = App.state.agents.length ? App.state.agents : [{ id: 'gpt-image', name: 'GPT-Image' }, { id: 'gemini', name: 'Gemini' }];
    sel.innerHTML = agents.map((a) =>
      `<option value="${esc(a.id)}">${esc(a.name || a.id)}</option>`).join('');
    const preferred = agents.find((a) => a.id === current) || agents.find((a) => String(a.id).startsWith('gpt-image'));
    if (preferred) sel.value = preferred.id;
  }

  function bindUpload(view) {
    const box = view.querySelector('#cuUpload');
    const input = box.querySelector('input[type=file]');
    box.addEventListener('click', () => input.click());
    input.addEventListener('change', () => {
      addFiles(Array.from(input.files));
      input.value = '';
    });
    // 拖拽上传：整个参考图卡片都是拖放区
    App.enableDropUpload(view.querySelector('#cuUploadStrip').closest('.card'), addFiles);
    $('#cuUploadStrip').addEventListener('click', (e) => {
      const x = e.target.closest('.thumb-x');
      if (!x) return;
      const thumb = x.closest('.thumb');
      const idx = Array.from($('#cuUploadStrip').querySelectorAll('.thumb')).indexOf(thumb);
      if (idx >= 0) state.images.splice(idx, 1);
      thumb.remove();
    });
  }

  function addFiles(files) {
    files.forEach((f) => {
      state.images.push(f);
      const url = URL.createObjectURL(f);
      $('#cuUploadStrip').insertAdjacentHTML('beforeend', `
          <div class="thumb" data-name="${esc(f.name)}">
            <img src="${url}"><button class="thumb-x" title="移除">×</button>
          </div>`);
    });
    if (files.length) App.toast(`已添加 ${files.length} 张参考图`);
  }

  /* ================= 产品类目树（EC_CATEGORY_TREE 级联选择） ================= */

  function catPathStr() { return state.catPath.join('>'); }

  function initCategoryTree(view) {
    view.querySelector('#cuCatBtn').addEventListener('click', () => {
      $('#cuCatPanel').classList.toggle('hidden');
      renderCatCols();
    });
    view.querySelector('#cuCatSearch').addEventListener('input', renderCatCols);
    view.querySelector('#cuCatClear').addEventListener('click', () => {
      state.catPath = [];
      state.sellings.clear();
      $('#cuCatText').textContent = '— 点击选择品类';
      $('#cuCatSellings').classList.add('hidden');
      $('#cuCatSearch').value = '';
      renderCatCols();
    });
    // 级联/卖点 点击委托
    view.querySelector('#cuCatCols').addEventListener('click', (e) => {
      const item = e.target.closest('.cat-item');
      if (!item) return;
      pickCat(Number(item.dataset.level), item.dataset.display, item.dataset.haschildren === 'true');
    });
    view.querySelector('#cuCatSellingChips').addEventListener('click', (e) => {
      if (!e.target.classList.contains('chip')) return;
      e.target.classList.toggle('on');
      const prompt = e.target.dataset.prompt;
      if (e.target.classList.contains('on')) state.sellings.add(prompt);
      else state.sellings.delete(prompt);
    });
  }

  function renderCatCols() {
    const tree = window.EC_CATEGORY_TREE || [];
    const q = $('#cuCatSearch').value.trim().toLowerCase();
    const box = $('#cuCatCols');
    if (q) {
      // 搜索模式：全树匹配路径
      const hits = [];
      (function walk(nodes, path) {
        for (const n of nodes) {
          const full = path ? path + ' > ' + n.display : n.display;
          if (n.display.toLowerCase().includes(q)) {
            hits.push({ full, leaf: !(n.children && n.children.length) });
          }
          if (n.children) walk(n.children, full);
        }
      })(tree, '');
      box.innerHTML = '<div class="cat-col">' +
        (hits.length ? hits.map((h) => `
          <div class="cat-item ${h.leaf ? '' : 'has-children'}" data-level="${h.full.split(' > ').length - 1}"
               data-display="${esc(h.full.split(' > ').pop())}" data-haschildren="${!h.leaf}"
               data-fullpath="${esc(h.full)}">
            <span class="cat-label cat-path-label">${esc(h.full)}</span>
          </div>`).join('') : '<div class="pending-line">无匹配品类</div>') +
        '</div>';
      // 搜索命中项：用完整路径直接选中
      box.querySelectorAll('.cat-item[data-fullpath]').forEach((el) => {
        el.addEventListener('click', () => {
          const segs = el.dataset.fullpath.split(' > ');
          state.catPath = segs;
          if (el.dataset.haschildren !== 'true') selectLeafCat();
          else { $('#cuCatSearch').value = ''; renderCatCols(); }
        }, { once: true });
      });
      return;
    }
    // 级联模式：按 catPath 逐层展开
    const cols = [];
    let nodes = tree;
    cols.push(buildCatCol(nodes, 0));
    for (let i = 0; i < state.catPath.length; i++) {
      const hit = (nodes || []).find((n) => n.display === state.catPath[i]);
      if (!hit || !hit.children || !hit.children.length) break;
      nodes = hit.children;
      cols.push(buildCatCol(nodes, i + 1));
    }
    box.innerHTML = cols.join('');
  }

  function buildCatCol(nodes, level) {
    return `<div class="cat-col">` + (nodes || []).map((n) => {
      const hasChildren = !!(n.children && n.children.length);
      const active = state.catPath[level] === n.display;
      return `<div class="cat-item ${hasChildren ? 'has-children' : ''} ${active ? 'active' : ''}"
        data-level="${level}" data-display="${esc(n.display)}" data-haschildren="${hasChildren}">
        <span class="cat-label">${esc(n.display)}</span>${hasChildren ? '<span class="cat-arrow">›</span>' : ''}
      </div>`;
    }).join('') + '</div>';
  }

  function pickCat(level, display, hasChildren) {
    state.catPath = state.catPath.slice(0, level);
    state.catPath[level] = display;
    if (hasChildren) { renderCatCols(); return; }
    selectLeafCat();
  }

  function selectLeafCat() {
    $('#cuCatPanel').classList.add('hidden');
    const path = catPathStr();
    $('#cuCatText').textContent = state.catPath.join(' > ');
    // 加载品类卖点
    const cat = window.ecCatalogGet ? window.ecCatalogGet(path) : { sellings: [] };
    const sellings = cat.sellings || [];
    state.sellings.clear();
    if (!sellings.length) {
      $('#cuCatSellings').classList.add('hidden');
      return;
    }
    $('#cuCatSellingChips').innerHTML = sellings.map((s) =>
      `<button class="chip" data-prompt="${esc(s.prompt || s.label)}">${esc(s.label || s.key)}</button>`).join('');
    $('#cuCatSellings').classList.remove('hidden');
  }

  /** 组装最终提示词：用户输入 + 类目 + 选中卖点 + 品类主体一致性 + 禁止段 + 无文字约束 */
  function buildFinalPrompt(userPrompt) {
    const path = catPathStr();
    const parts = [];
    if (userPrompt) parts.push(userPrompt);
    if (path) {
      parts.push('产品类目：' + state.catPath.join(' > '));
      if (state.sellings.size) parts.push('核心卖点：' + Array.from(state.sellings).join('；'));
      const lock = window.ecCatalogResolveUp ? window.ecCatalogResolveUp(path, 'subjectLock') : null;
      if (lock) parts.push(lock);
      const neg = window.ecCatalogResolveUp ? window.ecCatalogResolveUp(path, 'negative') : null;
      if (neg) parts.push(neg);
    }
    if ($('#cuNoText') && $('#cuNoText').checked) {
      parts.push('【禁止文字】画面中不要出现任何文字、字母、数字、水印、logo、品牌标识。');
    }
    return parts.filter(Boolean).join('\n');
  }

  /* ================= 生成主流程 ================= */

  async function run() {
    if (state.running) return;
    if (!state.images.length && !$('#cuPrompt').value.trim()) {
      App.toast('请至少上传一张参考图或填写提示词');
      return;
    }
    const skipAnalyze = $('#cuSkipAnalyze').checked;

    if (!skipAnalyze && state.images.length && !App.state.demoMode) {
      await runAnalysis();
    } else {
      await runGenerate(null);
    }
  }

  /** 第一步（可选）：Gemini 分析 */
  async function runAnalysis() {
    setRunning(true, 'Gemini 分析中…');
    try {
      const fd = new FormData();
      state.images.forEach((f) => fd.append('images', f));
      fd.append('prompt', $('#cuPrompt').value.trim());
      fd.append('count', String(Number($('#cuCount').value) || 1));
      fd.append('withText', $('#cuNoText').checked ? 'false' : 'true');
      const data = await window.YurenApi.customAnalyze(fd);
      if (data.error) throw new Error(data.error);
      state.analysisText = data.text || '';
      $('#cuAnalysisText').value = state.analysisText;
      $('#cuAnalysis').classList.remove('hidden');
      $('#cuEmpty').classList.add('hidden');
      App.flowChain('#cuFlow', ['上传与配置', '分析确认', '生成中', '完成'], 1);
      $('#cuConfirmGenerate').onclick = () => {
        $('#cuAnalysis').classList.add('hidden');
        runGenerate($('#cuAnalysisText').value);
      };
      App.toast('分析完成，请确认方案后生成');
    } catch (e) {
      App.toast('分析失败：' + e.message + '（可直接生成）');
    } finally {
      setRunning(false);
    }
  }

  /** 第二步：提交生成任务 */
  async function runGenerate(confirmedText) {
    setRunning(true, '任务提交中…');
    App.flowChain('#cuFlow', ['上传与配置', '分析确认', '生成中', '完成'], 2);
    $('#cuEmpty').classList.add('hidden');
    $('#cuResults').innerHTML = '';

    const fd = new FormData();
    state.images.forEach((f) => fd.append('images', f));
    const basePrompt = confirmedText != null ? confirmedText : ($('#cuPrompt').value.trim() || '白底商品图，专业摄影');
    fd.append('prompt', buildFinalPrompt(basePrompt));
    fd.append('count', String(Number($('#cuCount').value) || 1));
    fd.append('agentId', $('#cuAgent').value);
    fd.append('aspect', $('#cuAspect').value);
    fd.append('sessionId', App.state.sessionId);
    fd.append('mode', 'custom');
    const useLora = $('#cuLora').classList.contains('on');
    fd.append('useLora', String(useLora));
    if (useLora) fd.append('loraPreset', 'studio');

    try {
      if (App.state.demoMode) {
        await new Promise((r) => setTimeout(r, 800));
        runDemoTask(Number($('#cuCount').value) || 1);
        return;
      }
      const data = await window.YurenApi.customGenerate(fd);
      if (data.error || !data.taskId) throw new Error(data.error || '任务创建失败');
      state.taskId = data.taskId;
      showProgress(0, 1, '任务已创建');
      state.stopPoll = App.pollTask(data.taskId, {
        onProgress: (d) => showProgress(d.progress || 0, d.total || 1, d.currentProduct, d.status),
        onResult: (r) => {
          // 优先使用localPath(通过/api/image代理),fallback到output(COS直链)
          const raw = r.localPath || r.output;
          if (raw) {
            addImage(App.toImgUrl(raw), r.name, raw, 'custom');
          }
        },
        onDone: finishTask,
      });
      $('#cuMeta').textContent = `（任务 ${data.taskId.slice(0, 8)}…）`;
    } catch (e) {
      App.toast('生成失败：' + e.message);
      setRunning(false);
    }
  }

  /* ================= 局部重绘 ================= */

  let maskCtx = null, maskImg = null, painting = false;

  function openMaskEditor() {
    if (!state.images.length) { App.toast('请先上传一张参考图作为重绘底图'); return; }
    state.inpaint.image = state.images[0];
    $('#maskModal').classList.remove('hidden');

    const canvas = $('#maskCanvas');
    const url = URL.createObjectURL(state.inpaint.image);
    maskImg = new Image();
    maskImg.onload = () => {
      const maxW = Math.min(760, window.innerWidth - 220);
      const scale = Math.min(1, maxW / maskImg.width);
      canvas.width = maskImg.width * scale;
      canvas.height = maskImg.height * scale;
      maskCtx = canvas.getContext('2d');
      redrawMask();
    };
    maskImg.src = url;

    canvas.onmousedown = () => { painting = true; };
    canvas.onmouseup = canvas.onmouseleave = () => { painting = false; };
    canvas.onmousemove = (e) => {
      if (!painting || !maskCtx) return;
      const r = canvas.getBoundingClientRect();
      const size = Number($('#maskBrush').value);
      redrawMask();
      maskCtx.fillStyle = 'rgba(47, 107, 255, .45)';
      maskCtx.beginPath();
      maskCtx.arc(e.clientX - r.left, e.clientY - r.top, size / 2, 0, Math.PI * 2);
      maskCtx.fill();
      // 同步记录到底层蒙版
      paintToMaskLayer(e.clientX - r.left, e.clientY - r.top, size);
    };
  }

  let maskLayer = null; // 离屏二值蒙版
  function ensureMaskLayer() {
    const canvas = $('#maskCanvas');
    if (!maskLayer) {
      maskLayer = document.createElement('canvas');
      maskLayer.width = canvas.width;
      maskLayer.height = canvas.height;
      const ctx = maskLayer.getContext('2d');
      ctx.fillStyle = '#000';
      ctx.fillRect(0, 0, maskLayer.width, maskLayer.height);
    }
  }

  function paintToMaskLayer(x, y, size) {
    ensureMaskLayer();
    const ctx = maskLayer.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.beginPath();
    ctx.arc(x, y, size / 2, 0, Math.PI * 2);
    ctx.fill();
  }

  function redrawMask() {
    if (!maskCtx || !maskImg) return;
    const canvas = $('#maskCanvas');
    maskCtx.drawImage(maskImg, 0, 0, canvas.width, canvas.height);
    if (maskLayer) maskCtx.drawImage(tintedMask(), 0, 0);
  }

  function tintedMask() {
    // 把二值蒙版染成半透明蓝，便于预览
    const t = document.createElement('canvas');
    t.width = maskLayer.width;
    t.height = maskLayer.height;
    const ctx = t.getContext('2d');
    ctx.drawImage(maskLayer, 0, 0);
    ctx.globalCompositeOperation = 'source-in';
    ctx.fillStyle = 'rgba(47, 107, 255, .45)';
    ctx.fillRect(0, 0, t.width, t.height);
    return t;
  }

  function clearMask() {
    if (!maskLayer) return;
    const ctx = maskLayer.getContext('2d');
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, maskLayer.width, maskLayer.height);
    redrawMask();
  }

  async function submitInpaint() {
    const prompt = $('#maskPrompt').value.trim();
    if (!prompt) { App.toast('请填写重绘指令'); return; }
    if (!maskLayer) { App.toast('请先涂抹要重绘的区域'); return; }

    const canvas = $('#maskCanvas');
    const toBlob = (cv, name) => new Promise((res) => cv.toBlob((b) => res(new File([b], name, { type: 'image/png' })), 'image/png'));

    // 原图：按画布尺寸导出（与原图同比例）
    const imgCanvas = document.createElement('canvas');
    imgCanvas.width = canvas.width;
    imgCanvas.height = canvas.height;
    imgCanvas.getContext('2d').drawImage(maskImg, 0, 0, canvas.width, canvas.height);

    const [imageFile, maskFile] = await Promise.all([
      toBlob(imgCanvas, 'image.png'),
      toBlob(maskLayer, 'mask.png'),
    ]);

    $('#maskModal').classList.add('hidden');
    App.toast('局部重绘提交中…');
    try {
      let resultUrl, resultRaw = null;
      if (App.state.demoMode) {
        await new Promise((r) => setTimeout(r, 1500));
        resultUrl = URL.createObjectURL(state.inpaint.image);
      } else {
        const fd = new FormData();
        fd.append('image', imageFile);
        fd.append('mask', maskFile);
        fd.append('prompt', prompt);
        fd.append('sessionId', App.state.sessionId);
        const data = await window.YurenApi.inpaint(fd);
        if (data.error) throw new Error(data.error);
        resultRaw = (data.results || [])[0] || null;
        resultUrl = App.toImgUrl(resultRaw);
      }
      $('#cuEmpty').classList.add('hidden');
      addImage(resultUrl, '局部重绘', resultRaw, 'inpaint');
      App.toast('重绘完成');
    } catch (e) {
      App.toast('重绘失败：' + e.message);
    }
  }

  /* ================= 公共 ================= */

  function showProgress(done, total, current, status) {
    const pct = total ? Math.round((done / total) * 100) : 0;
    $('#cuProgress').innerHTML = `
      <div class="task-progress">
        <b>${status === 'stopping' ? '正在停止…' : '生成中'}</b>
        <div class="tp-bar"><i style="width:${pct}%"></i></div>
        <div class="tp-meta"><span>${current ? '正在处理：' + esc(current) : ''}</span><span>${done}/${total}</span></div>
        <button class="btn btn-ghost" id="cuStop" style="margin-top:8px">停止任务</button>
      </div>`;
    const stopBtn = $('#cuStop');
    if (stopBtn) stopBtn.addEventListener('click', async () => {
      try {
        if (state.taskId && !App.state.demoMode) await window.YurenApi.stopTask(state.taskId);
        App.toast('已发送停止指令');
      } catch (e) { App.toast('停止失败：' + e.message); }
    });
  }

  function addImage(url, name, raw, mode) {
    const publishBtn = raw
      ? `<button class="ri-btn" data-publish-asset="${esc(raw)}" data-title="${esc(name || '')}" data-type="image" data-mode="${esc(mode || 'custom')}">☁ 存入企业库</button>`
      : '';
    $('#cuResults').insertAdjacentHTML('beforeend', `
      <div class="result-item">
        <img src="${esc(url)}" alt="${esc(name || '生成图')}" loading="lazy">
        <div class="ri-meta"><span>${esc(name || '')}</span><a href="${esc(url)}" target="_blank">查看</a></div>
        ${publishBtn ? `<div class="ri-actions">${publishBtn}</div>` : ''}
      </div>`);
  }

  function finishTask(data) {
    const ok = (data.results || []).filter((r) => r.status === 'success' && !String(r.name).startsWith('__')).length;
    App.flowChain('#cuFlow', ['上传与配置', '分析确认', '生成中', '完成'], 4);
    App.toast(`任务结束：成功 ${ok} 项`);
    $('#cuProgress').innerHTML = '';
    setRunning(false);
  }

  function setRunning(running, text) {
    state.running = running;
    const btn = $('#cuRun');
    btn.disabled = running;
    btn.classList.toggle('loading', running);
    btn.textContent = running ? (text || '处理中…') : '开始生成';
  }

  function runDemoTask(count) {
    let done = 0;
    showProgress(0, count, '演示任务');
    const timer = setInterval(() => {
      done++;
      showProgress(done, count, '演示任务', 'running');
      if (done >= count) {
        clearInterval(timer);
        finishTask({ results: [{ name: 'demo', status: 'success' }] });
        $('#cuMeta').textContent = '（演示模式：未真实生图）';
      }
    }, 1000);
  }

  App.registerView('custom', { init });
})();
