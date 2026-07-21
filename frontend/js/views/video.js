/**
 * 视频模式视图 — AI 视频生成
 * 接口：GET /api/video/models、POST /api/video/generate(FormData)、
 *       GET /api/task/{id} 轮询（结果 type=video, filename）、GET /api/video/file?filename=
 */
(function () {
  const App = window.App;
  const $ = App.$, esc = App.escapeHtml;

  // 本地兜底模型目录（与老前端一致：接口失败或非 6 个时使用）
  const FALLBACK_MODELS = [
    { id: 'veo-3.1-generate-preview', name: 'Veo 3.1', provider: 'Google', inputMode: 'flexible', configured: true },
    { id: 'doubao-seedance-2-0', name: 'Seedance 2.0', provider: '火山方舟', inputMode: 'flexible', configured: true },
    { id: 'grok-text-video', name: 'Grok 文生视频', provider: '随想', inputMode: 'text_only', configured: true },
    { id: 'grok-image-video', name: 'Grok 图生视频', provider: '随想', inputMode: 'image_only', configured: true },
    { id: 'jimeng-sd2-fast', name: '即梦 SD2.0-Fast', provider: '即梦', inputMode: 'flexible', configured: true },
    { id: 'jimeng-ds2', name: '即梦 Video DS 2.0', provider: '即梦', inputMode: 'flexible', configured: true },
  ];

  const state = {
    models: [],
    images: [],
    running: false,
    taskId: null,
    stopPoll: null,
  };

  function init(view) {
    view.querySelector('#videoBody').innerHTML = `
      <div class="split-pane">
        <section class="panel">
          <div class="card">
            <div class="card-head"><b>视频模型</b></div>
            <div id="vdModelList" class="vd-model-list"><div class="pending-line">加载中…</div></div>
          </div>
          <div class="card">
            <div class="card-head"><b>参考图</b><small class="muted" id="vdImgHint"></small></div>
            <div class="upload-strip" id="vdUploadStrip">
              <div class="upload-box small" id="vdUpload">
                <span class="upload-ico">＋</span><small>上传图片</small>
                <input type="file" accept="image/*" multiple hidden>
              </div>
            </div>
          </div>
          <div class="card">
            <div class="card-head"><b>生成配置</b></div>
            <textarea id="vdPrompt" class="goal-text" rows="3"
              placeholder="描述视频内容，如：花洒出水特写，水流晶莹剔透，慢镜头…"></textarea>
            <div class="form-row"><label>画面比例</label>
              <select id="vdAspect"><option>16:9</option><option>9:16</option><option>1:1</option></select>
            </div>
            <div class="form-row"><label>时长（秒）</label>
              <select id="vdDuration"><option>4</option><option selected>8</option><option>10</option></select>
            </div>
            <button class="btn btn-primary btn-block" id="vdRun">开始生成视频</button>
          </div>
        </section>
        <section class="panel">
          <h2 class="panel-title">生成结果 <small class="panel-sub" id="vdMeta"></small></h2>
          <div id="vdFlow"></div>
          <div id="vdProgress"></div>
          <div id="vdResults"></div>
          <div class="output-empty" id="vdEmpty">
            <div class="empty-ico">▶</div>
            <p>选择视频模型并填写描述后，点击「开始生成视频」</p>
            <p class="empty-sub">接口：<code>POST /api/video/generate</code></p>
          </div>
        </section>
      </div>`;

    bindUpload(view);
    view.querySelector('#vdRun').addEventListener('click', run);
    App.flowChain('#vdFlow', ['配置', '提交', '视频生成', '完成'], 0);
    loadModels();
  }

  async function loadModels() {
    const box = $('#vdModelList');
    if (App.state.demoMode) {
      state.models = FALLBACK_MODELS;
    } else {
      try {
        const list = await window.YurenApi.videoModels();
        state.models = (Array.isArray(list) && list.length === 6) ? list : FALLBACK_MODELS;
      } catch {
        state.models = FALLBACK_MODELS;
      }
    }
    box.innerHTML = state.models.map((m, i) => `
      <label class="vd-model ${m.configured === false ? 'disabled' : ''}">
        <input type="radio" name="vdModel" value="${esc(m.id)}" ${i === 0 ? 'checked' : ''} ${m.configured === false ? 'disabled' : ''}>
        <div class="vdm-info">
          <div class="vdm-name">${esc(m.name)} ${m.configured === false ? '<span class="tag-off">未配置</span>' : '<span class="tag-ok">可用</span>'}</div>
          <div class="vdm-sub">${esc(m.provider || '')} · ${inputModeLabel(m.inputMode)}</div>
        </div>
      </label>`).join('');
    box.addEventListener('change', updateImgHint);
    updateImgHint();
  }

  function inputModeLabel(mode) {
    return { text_only: '仅文字', image_only: '必须 1 张参考图', flexible: '文字/图片均可' }[mode] || mode;
  }

  function selectedModel() {
    const id = document.querySelector('input[name="vdModel"]:checked')?.value;
    return state.models.find((m) => m.id === id);
  }

  function updateImgHint() {
    const m = selectedModel();
    $('#vdImgHint').textContent = m ? `（${inputModeLabel(m.inputMode)}）` : '';
  }

  function bindUpload(view) {
    const box = view.querySelector('#vdUpload');
    const input = box.querySelector('input[type=file]');
    box.addEventListener('click', () => input.click());
    input.addEventListener('change', () => {
      addFiles(Array.from(input.files));
      input.value = '';
    });
    // 拖拽上传：整个参考图卡片都是拖放区
    App.enableDropUpload(view.querySelector('#vdUploadStrip').closest('.card'), addFiles);
    $('#vdUploadStrip').addEventListener('click', (e) => {
      const x = e.target.closest('.thumb-x');
      if (!x) return;
      const idx = Array.from($('#vdUploadStrip').querySelectorAll('.thumb')).indexOf(x.closest('.thumb'));
      if (idx >= 0) state.images.splice(idx, 1);
      x.closest('.thumb').remove();
    });
  }

  function addFiles(files) {
    files.forEach((f) => {
      state.images.push(f);
      const url = URL.createObjectURL(f);
      $('#vdUploadStrip').insertAdjacentHTML('beforeend', `
          <div class="thumb"><img src="${url}"><button class="thumb-x" title="移除">×</button></div>`);
    });
    if (files.length) App.toast(`已添加 ${files.length} 张参考图`);
  }

  async function run() {
    if (state.running) return;
    const m = selectedModel();
    const prompt = $('#vdPrompt').value.trim();
    if (!prompt) { App.toast('请填写视频描述'); return; }
    if (m) {
      if (m.inputMode === 'text_only' && state.images.length) { App.toast(m.name + ' 不支持参考图，请移除图片'); return; }
      if (m.inputMode === 'image_only' && state.images.length !== 1) { App.toast(m.name + ' 必须上传且只能上传 1 张参考图'); return; }
    }

    setRunning(true);
    App.flowChain('#vdFlow', ['配置', '提交', '视频生成', '完成'], 1);
    $('#vdEmpty').classList.add('hidden');
    $('#vdResults').innerHTML = '';
    showProgress('视频生成中（视频任务通常需要 1~5 分钟）…');

    const fd = new FormData();
    fd.append('model', m ? m.id : 'veo-3.1-generate-preview');
    fd.append('prompt', prompt);
    fd.append('aspectRatio', $('#vdAspect').value);
    fd.append('durationSeconds', $('#vdDuration').value);
    fd.append('sessionId', App.state.sessionId);
    state.images.forEach((f) => fd.append('images', f));

    try {
      if (App.state.demoMode) {
        await new Promise((r) => setTimeout(r, 800));
        runDemoTask();
        return;
      }
      const data = await window.YurenApi.videoGenerate(fd);
      if (data.error || !data.taskId) throw new Error(data.error || data.message || '任务创建失败');
      state.taskId = data.taskId;
      App.flowChain('#vdFlow', ['配置', '提交', '视频生成', '完成'], 2);
      state.stopPoll = App.pollTask(data.taskId, {
        onProgress: (d) => showProgress('视频生成中… ' + (d.currentProduct || ''), d.status),
        onResult: () => {},
        onDone: (d) => {
          App.flowChain('#vdFlow', ['配置', '提交', '视频生成', '完成'], 4);
          const video = (d.results || []).find((r) => r.type === 'video' && r.filename);
          const err = (d.results || []).find((r) => r.type === 'video' && r.status === 'error');
          if (video) {
            addVideo(window.YurenApi.videoFileUrl(video.filename), video.filename);
            App.toast('视频生成完成');
          } else if (err) {
            App.toast('视频生成失败：' + (err.message || '未知错误'));
          }
          $('#vdProgress').innerHTML = '';
          setRunning(false);
        },
      });
      $('#vdMeta').textContent = `（任务 ${data.taskId.slice(0, 8)}…）`;
    } catch (e) {
      App.toast('提交失败：' + e.message);
      $('#vdProgress').innerHTML = '';
      setRunning(false);
    }
  }

  function showProgress(text, status) {
    $('#vdProgress').innerHTML = `
      <div class="task-progress">
        <b>${status === 'stopping' ? '正在停止…' : '视频任务'}</b>
        <div class="tp-meta" style="margin-top:8px"><span>${esc(text || '')}</span></div>
        <div class="tp-bar"><i style="width:100%;animation:shimmerBar 1.6s linear infinite"></i></div>
        <button class="btn btn-ghost" id="vdStop" style="margin-top:8px">停止任务</button>
      </div>`;
    const stopBtn = $('#vdStop');
    if (stopBtn) stopBtn.addEventListener('click', async () => {
      try {
        if (state.taskId && !App.state.demoMode) await window.YurenApi.stopTask(state.taskId);
        App.toast('已发送停止指令');
      } catch (e) { App.toast('停止失败：' + e.message); }
    });
  }

  function addVideo(url, filename) {
    $('#vdResults').innerHTML = `
      <div class="card reveal">
        <div class="card-head"><b>生成视频</b><small class="muted">${esc(filename)}</small></div>
        <video controls preload="metadata" style="width:100%;border-radius:8px;background:#000" src="${esc(url)}"></video>
        <div class="ri-actions" style="padding:10px 0 0">
          <a class="btn btn-outline" href="${esc(url)}" download="${esc(filename)}">⬇ 下载到电脑</a>
          <button class="ri-btn" data-publish-asset="${esc(filename)}" data-title="${esc(filename)}" data-type="video" data-mode="video">☁ 存入企业库</button>
        </div>
      </div>`;
  }

  function setRunning(running) {
    state.running = running;
    const btn = $('#vdRun');
    btn.disabled = running;
    btn.classList.toggle('loading', running);
    btn.textContent = running ? '生成中…' : '开始生成视频';
  }

  function runDemoTask() {
    showProgress('演示任务运行中…', 'running');
    setTimeout(() => {
      $('#vdProgress').innerHTML = '';
      $('#vdMeta').textContent = '（演示模式：未真实生成视频）';
      setRunning(false);
      App.toast('演示模式：视频任务流程演示完成');
    }, 3000);
  }

  App.registerView('video', { init });
})();
