/**
 * 辅助视图集合：工作台 / 素材与资产中心 / 模型与插件中心 / 任务中心 / 企业设置
 * 接口：/api/history/*、/api/kaipin_materials、/api/gallery、/api/agents、
 *       /api/video/models、/api/settings
 */
(function () {
  const App = window.App;
  const $ = App.$, esc = App.escapeHtml;

  /* ==================== 工作台 ==================== */

  App.registerView('workspace', {
    init(view) {
      view.querySelector('#workspaceBody').innerHTML = `
        <div class="view-grid" id="wsStats">
          <div class="stat-card"><div class="stat-label">生图模型</div><div class="stat-num" id="wsAgentCount">—</div></div>
          <div class="stat-card"><div class="stat-label">视频模型</div><div class="stat-num" id="wsVideoCount">—</div></div>
          <div class="stat-card"><div class="stat-label">素材库</div><div class="stat-num" id="wsMaterialCount">—</div></div>
          <div class="stat-card"><div class="stat-label">历史生成</div><div class="stat-num" id="wsHistoryCount">—</div></div>
        </div>
        <h2 class="panel-title" style="margin:22px 0 12px">快捷入口</h2>
        <div class="view-grid">
          <div class="stat-card ws-entry" data-goto="kaipin"><div class="ws-ico">✦</div><b>开品模式</b><div class="stat-label">外观创新设计工作台</div></div>
          <div class="stat-card ws-entry" data-goto="ecommerce"><div class="ws-ico">◉</div><b>电商模式</b><div class="stat-label">钉钉产品图批量生成</div></div>
          <div class="stat-card ws-entry" data-goto="custom"><div class="ws-ico">◈</div><b>自定义模式</b><div class="stat-label">参考图风格迁移</div></div>
          <div class="stat-card ws-entry" data-goto="video"><div class="ws-ico">▶</div><b>视频模式</b><div class="stat-label">AI 视频生成</div></div>
        </div>
        <h2 class="panel-title" style="margin:22px 0 12px">最近生成</h2>
        <div id="wsRecent" class="result-grid"><div class="pending-line">加载中…</div></div>`;

      view.querySelectorAll('.ws-entry').forEach((el) =>
        el.addEventListener('click', () => App.gotoView(el.dataset.goto)));
    },
    onShow() {
      $('#wsAgentCount').textContent = App.state.agents.length || '—';
      if (App.state.demoMode) {
        $('#wsVideoCount').textContent = '6';
        $('#wsMaterialCount').textContent = '—';
        $('#wsHistoryCount').textContent = '—';
        $('#wsRecent').innerHTML = '<div class="pending-line">演示模式：无历史数据</div>';
        return;
      }
      window.YurenApi.videoModels().then((l) => {
        $('#wsVideoCount').textContent = Array.isArray(l) ? l.length : '—';
      }).catch(() => { $('#wsVideoCount').textContent = '—'; });
      window.YurenApi.kaipinMaterials(1).then((d) => {
        $('#wsMaterialCount').textContent = (d.items || []).length ? (d.items.length + '+') : '0';
      }).catch(() => { $('#wsMaterialCount').textContent = '—'; });
      window.YurenApi.historyGenerations({ size: 8 }).then((d) => {
        $('#wsHistoryCount').textContent = d.totalElements ?? '—';
        const items = (d.items || []).filter((it) => it.outputPath);
        if (!items.length) {
          $('#wsRecent').innerHTML = '<div class="pending-line">暂无生成记录</div>';
          return;
        }
        $('#wsRecent').innerHTML = items.map((it) => `
          <div class="result-item">
            <img src="${window.YurenApi.historyThumbnailUrl(it.id)}" alt="${esc(it.mode || '')}" loading="lazy">
            <div class="ri-meta"><span>${esc(modeLabel(it.mode))}</span><span>${esc(String(it.createdAt || '').slice(5, 16))}</span></div>
          </div>`).join('');
      }).catch(() => { $('#wsRecent').innerHTML = '<div class="pending-line">历史加载失败</div>'; });
    },
  });

  function modeLabel(m) {
    return { standard: '标准', custom: '自定义', ecommerce: '电商', product: '开品', kaipin: '开品', inpaint: '重绘', video: '视频' }[m] || m || '';
  }

  /* ==================== 素材与资产中心 ==================== */

  App.registerView('assets', {
    init(view) {
      view.querySelector('#assetsBody').innerHTML = `
        <div class="tab-bar">
          <button class="tab on" data-tab="assets">企业资产库</button>
          <button class="tab" data-tab="materials">开品素材库</button>
          <button class="tab" data-tab="gallery">生成图库</button>
        </div>
        <div id="tabAssets">
          <div class="card" style="margin-bottom:14px">
            <div class="card-head"><b>企业资产库</b><small class="muted">（全公司共享的生成资产）</small>
              <div class="chip-group" style="margin-left:auto" id="assetTypeFilter">
                <button class="chip on" data-type="">全部</button>
                <button class="chip" data-type="image">图片</button>
                <button class="chip" data-type="video">视频</button>
              </div>
            </div>
          </div>
          <div id="companyAssetGrid" class="result-grid"><div class="pending-line">加载中…</div></div>
        </div>
        <div id="tabMaterials" class="hidden">
          <div class="card" style="margin-bottom:14px">
            <div class="card-head"><b>上传素材</b><small class="muted">（图 + 提示词，供开品模式引用）</small></div>
            <div class="mask-tools">
              <input type="file" id="mtFile" accept="image/*">
              <input id="mtTitle" class="spec-input" placeholder="素材标题（可选）" style="max-width:180px">
              <input id="mtPrompt" class="spec-input" placeholder="素材提示词（可选）">
              <button class="btn btn-primary" id="mtUpload">上传</button>
            </div>
          </div>
          <div id="materialGrid" class="result-grid"><div class="pending-line">加载中…</div></div>
        </div>
        <div id="tabGallery" class="hidden">
          <div class="card" style="margin-bottom:14px">
            <div class="card-head"><b>图库浏览</b><small class="muted" id="galPath"></small>
              <button class="link-btn" id="galUp">↑ 上级目录</button>
            </div>
          </div>
          <div id="galleryGrid" class="result-grid"><div class="pending-line">点击标签加载…</div></div>
        </div>`;

      const tabs = view.querySelectorAll('.tab');
      tabs.forEach((t) => t.addEventListener('click', () => {
        tabs.forEach((x) => x.classList.toggle('on', x === t));
        $('#tabAssets').classList.toggle('hidden', t.dataset.tab !== 'assets');
        $('#tabMaterials').classList.toggle('hidden', t.dataset.tab !== 'materials');
        $('#tabGallery').classList.toggle('hidden', t.dataset.tab !== 'gallery');
        if (t.dataset.tab === 'gallery' && !this._galleryLoaded) {
          this._galleryLoaded = true;
          loadGallery('');
        }
        if (t.dataset.tab === 'materials') loadMaterials();
        if (t.dataset.tab === 'assets') loadCompanyAssets();
      }));

      view.querySelector('#mtUpload').addEventListener('click', uploadMaterial);
      // 拖拽上传素材：拖入即填入文件框
      App.enableDropUpload(view.querySelector('#tabMaterials .card'), (files) => {
        const dt = new DataTransfer();
        dt.items.add(files[0]);
        view.querySelector('#mtFile').files = dt.files;
        App.toast('素材图片已就绪，点「上传」入库');
      });
      view.querySelector('#assetTypeFilter').addEventListener('click', (e) => {
        if (!e.target.classList.contains('chip')) return;
        view.querySelectorAll('#assetTypeFilter .chip').forEach((c) => c.classList.toggle('on', c === e.target));
        loadCompanyAssets();
      });
    },
    onShow() { loadCompanyAssets(); },
  });

  /** 企业资产库列表 */
  async function loadCompanyAssets() {
    const grid = $('#companyAssetGrid');
    if (!grid) return;
    if (App.state.demoMode) {
      grid.innerHTML = '<div class="pending-line">演示模式：无企业资产（接口 GET /api/assets）</div>';
      return;
    }
    const type = document.querySelector('#assetTypeFilter .chip.on')?.dataset.type || '';
    try {
      const d = await window.YurenApi.assets(type ? { type } : {});
      const items = d.items || [];
      if (!items.length) {
        grid.innerHTML = '<div class="pending-line">企业资产库为空 —— 在生成结果上点「存入企业库」即可入库</div>';
        return;
      }
      const me = App.state.user || {};
      grid.innerHTML = items.map((a) => {
        const canDel = a.uploaderId === me.id || String(me.role).toUpperCase() === 'ADMIN';
        const media = a.type === 'video'
          ? `<video src="${a.url}" preload="metadata" muted style="width:100%;aspect-ratio:1;object-fit:cover;background:#000"></video>`
          : `<img src="${a.url}" alt="${esc(a.title || '资产')}" loading="lazy">`;
        return `
        <div class="result-item">
          ${media}
          <div class="ri-meta">
            <span title="${esc(a.title || '')}">${esc(a.title || '未命名')} · ${esc(a.uploaderName || '')}</span>
            <span>
              <a href="${a.downloadUrl}" title="下载到电脑">⬇</a>
              ${canDel ? `<a href="javascript:;" data-asset-del="${a.id}" title="删除">删</a>` : ''}
            </span>
          </div>
        </div>`;
      }).join('');
      grid.querySelectorAll('[data-asset-del]').forEach((el) => el.addEventListener('click', async () => {
        if (!confirm('确认从企业库删除该资产？')) return;
        try {
          const r = await window.YurenApi.deleteAsset(el.dataset.assetDel);
          if (r.success === false || r.error) throw new Error(r.error || '删除失败');
          App.toast('已删除');
          loadCompanyAssets();
        } catch (e) { App.toast('删除失败：' + e.message); }
      }));
    } catch (e) {
      grid.innerHTML = `<div class="pending-line error">加载失败：${esc(e.message)}</div>`;
    }
  }

  async function loadMaterials() {
    const grid = $('#materialGrid');
    if (!grid) return;
    if (App.state.demoMode) {
      grid.innerHTML = '<div class="pending-line">演示模式：无素材数据（接口 GET /api/kaipin_materials）</div>';
      return;
    }
    try {
      const d = await window.YurenApi.kaipinMaterials();
      const items = d.items || [];
      if (!items.length) { grid.innerHTML = '<div class="pending-line">素材库为空，先上传一个吧</div>'; return; }
      grid.innerHTML = items.map((m) => `
        <div class="result-item">
          <img src="${App.toImgUrl(m.imagePath)}" alt="${esc(m.title || '素材')}" loading="lazy">
          <div class="ri-meta"><span>${esc(m.title || m.originalName || '未命名')}</span>
            <a href="javascript:;" data-del="${m.id}">删除</a></div>
        </div>`).join('');
      grid.querySelectorAll('[data-del]').forEach((a) => a.addEventListener('click', async () => {
        if (!confirm('确认删除该素材？')) return;
        try {
          await window.YurenApi.deleteKaipinMaterial(a.dataset.del);
          loadMaterials();
          App.toast('已删除');
        } catch (e) { App.toast('删除失败：' + e.message); }
      }));
    } catch (e) {
      grid.innerHTML = `<div class="pending-line error">加载失败：${esc(e.message)}</div>`;
    }
  }

  async function uploadMaterial() {
    const file = $('#mtFile').files[0];
    if (!file) { App.toast('请先选择图片'); return; }
    const fd = new FormData();
    fd.append('image', file);
    fd.append('title', $('#mtTitle').value.trim());
    fd.append('prompt', $('#mtPrompt').value.trim());
    try {
      const d = await window.YurenApi.addKaipinMaterial(fd);
      if (d.error || d.success === false) throw new Error(d.error || '上传失败');
      App.toast('素材已上传');
      $('#mtFile').value = '';
      $('#mtTitle').value = '';
      $('#mtPrompt').value = '';
      loadMaterials();
    } catch (e) { App.toast('上传失败：' + e.message); }
  }

  async function loadGallery(path) {
    const grid = $('#galleryGrid');
    grid.innerHTML = '<div class="pending-line">加载中…</div>';
    try {
      const d = await window.YurenApi.gallery(path);
      $('#galPath').textContent = d.path || d.root || '';
      const items = d.items || [];
      if (!items.length) { grid.innerHTML = '<div class="pending-line">空目录</div>'; return; }
      grid.innerHTML = items.map((it) => it.type === 'folder' ? `
        <div class="result-item folder" data-folder="${esc(it.path)}">
          <div class="folder-ico">📁</div>
          <div class="ri-meta"><span>${esc(it.name)}（${it.count ?? 0}）</span></div>
        </div>` : `
        <div class="result-item">
          <img src="${App.toImgUrl(it.path)}" alt="${esc(it.name)}" loading="lazy">
          <div class="ri-meta"><span>${esc(it.name)}</span><a href="${App.toImgUrl(it.path)}" target="_blank">查看</a></div>
        </div>`).join('');
      grid.querySelectorAll('[data-folder]').forEach((el) =>
        el.addEventListener('click', () => loadGallery(el.dataset.folder)));
      $('#galUp').onclick = () => {
        const parent = String(d.path || '').replace(/[\\/][^\\/]+$/, '');
        loadGallery(parent === d.path ? '' : parent);
      };
    } catch (e) {
      grid.innerHTML = `<div class="pending-line error">加载失败：${esc(e.message)}</div>`;
    }
  }

  /* ==================== 模型与插件中心 ==================== */

  App.registerView('models', {
    init(view) {
      view.querySelector('#modelsBody').innerHTML = `
        <h2 class="panel-title">生图模型 <small class="panel-sub">（GET /api/agents）</small></h2>
        <div class="view-grid" id="imgModelGrid" style="margin-bottom:22px"><div class="pending-line">加载中…</div></div>
        <h2 class="panel-title">视频模型 <small class="panel-sub">（GET /api/video/models）</small></h2>
        <div class="view-grid" id="videoModelGrid"><div class="pending-line">加载中…</div></div>`;
    },
    onShow() {
      const imgGrid = $('#imgModelGrid');
      const agents = App.state.agents;
      imgGrid.innerHTML = agents.length ? agents.map((a) => `
        <div class="stat-card model-card">
          <div class="mc-ico">🎨</div>
          <div><div class="mc-name">${esc(a.name || a.id)}</div>
          <div class="mc-sub">${esc(a.id)}</div></div>
          <span class="tag-ok" style="margin-left:auto">已接入</span>
        </div>`).join('') : '<div class="pending-line">演示模式：无模型数据</div>';

      const videoGrid = $('#videoModelGrid');
      if (App.state.demoMode) {
        videoGrid.innerHTML = '<div class="pending-line">演示模式：无模型数据</div>';
        return;
      }
      window.YurenApi.videoModels().then((list) => {
        if (!Array.isArray(list) || !list.length) {
          videoGrid.innerHTML = '<div class="pending-line">无视频模型</div>';
          return;
        }
        videoGrid.innerHTML = list.map((m) => `
          <div class="stat-card model-card">
            <div class="mc-ico">🎬</div>
            <div><div class="mc-name">${esc(m.name)}</div>
            <div class="mc-sub">${esc(m.provider || '')} · ${esc(m.inputMode || '')}</div></div>
            <span class="${m.configured ? 'tag-ok' : 'tag-off'}" style="margin-left:auto">${m.configured ? '可用' : '未配置'}</span>
          </div>`).join('');
      }).catch(() => { videoGrid.innerHTML = '<div class="pending-line">加载失败</div>'; });
    },
  });

  /* ==================== 任务中心 ==================== */

  App.registerView('tasks', {
    init(view) {
      view.querySelector('#tasksBody').innerHTML = `
        <div class="card">
          <div class="card-head"><b>任务查询</b><small class="muted">（后端任务为内存态，按 ID 查询）</small></div>
          <div class="mask-tools">
            <input id="taskIdInput" class="spec-input" placeholder="输入任务 ID（生成时页面会显示）">
            <button class="btn btn-primary" id="taskQueryBtn">查询状态</button>
          </div>
          <div id="taskQueryResult" style="margin-top:14px"></div>
        </div>
        <div class="output-empty">
          <div class="empty-ico">☰</div>
          <p>在各模式中发起的生成任务会显示任务 ID，可在此查询进度或停止</p>
          <p class="empty-sub">接口：<code>GET /api/task/{taskId}</code> · 任务列表接口后端暂未提供</p>
        </div>`;

      view.querySelector('#taskQueryBtn').addEventListener('click', async () => {
        const id = $('#taskIdInput').value.trim();
        if (!id) { App.toast('请输入任务 ID'); return; }
        const box = $('#taskQueryResult');
        box.innerHTML = '<div class="pending-line">查询中…</div>';
        try {
          const d = await window.YurenApi.taskStatus(id);
          box.innerHTML = `
            <table class="data-table">
              <tr><th>任务 ID</th><td>${esc(d.taskId || id)}</td></tr>
              <tr><th>状态</th><td>${esc(d.status || '—')}</td></tr>
              <tr><th>进度</th><td>${d.progress ?? 0} / ${d.total ?? 0}</td></tr>
              <tr><th>当前处理</th><td>${esc(d.currentProduct || '—')}</td></tr>
              <tr><th>结果数</th><td>${(d.results || []).length}</td></tr>
            </table>`;
        } catch (e) {
          box.innerHTML = `<div class="pending-line error">查询失败：${esc(e.message)}（任务可能已过期清理）</div>`;
        }
      });
    },
  });

  /* ==================== 企业设置 ==================== */

  App.registerView('settings', {
    init(view) {
      view.querySelector('#settingsBody').innerHTML = `
        <div class="split-pane" style="grid-template-columns:1fr 1fr">
          <section>
            <div class="card">
              <div class="card-head"><b>钉钉多维表格</b><small class="muted">（管理员）</small></div>
              <div class="form-row"><label>App Key</label><input id="stAppKey"></div>
              <div class="form-row"><label>App Secret</label><input id="stAppSecret" type="password"></div>
              <div class="form-row"><label>Union ID</label><input id="stUnionId"></div>
              <div class="form-row"><label>App UUID</label><input id="stAppUuid"></div>
              <div class="form-row"><label>Sheet ID</label><input id="stSheetId"></div>
              <button class="btn btn-primary btn-block" id="stSaveDing">保存钉钉配置</button>
            </div>
            <div class="card">
              <div class="card-head"><b>网络代理</b><small class="muted">（管理员）</small></div>
              <div class="form-row"><label>主机</label><input id="stProxyHost" placeholder="127.0.0.1"></div>
              <div class="form-row"><label>端口</label><input id="stProxyPort" placeholder="7890"></div>
              <button class="btn btn-primary btn-block" id="stSaveProxy">保存代理配置</button>
            </div>
          </section>
          <section>
            <div class="card">
              <div class="card-head"><b>文件保存位置</b></div>
              <div class="form-row"><label>输出目录</label><input id="stOutputDir" placeholder="留空使用默认目录"></div>
              <button class="btn btn-primary btn-block" id="stSaveDir">保存目录配置</button>
            </div>
            <div class="card">
              <div class="card-head"><b>账号信息</b></div>
              <div id="stAccount" class="hint-line">—</div>
            </div>
          </section>
        </div>

        <!-- 员工管理（仅管理员可见） -->
        <div id="staffSection" class="hidden" style="margin-top:16px">
          <div class="card">
            <div class="card-head"><b>员工管理</b><small class="muted">（管理员专属：纳新 / 停用 / 重置密码 / 分配点数）</small></div>
            <div class="mask-tools" style="margin-bottom:14px">
              <input id="nuUsername" class="spec-input" placeholder="登录账号" style="max-width:150px">
              <input id="nuDisplayName" class="spec-input" placeholder="姓名" style="max-width:120px">
              <input id="nuPassword" class="spec-input" type="password" placeholder="初始密码" style="max-width:150px">
              <select id="nuRole" style="border:1px solid var(--border);border-radius:6px;padding:7px 10px">
                <option value="USER">员工</option><option value="ADMIN">管理员</option>
              </select>
              <button class="btn btn-primary" id="nuCreate">＋ 纳入新员工</button>
            </div>
            <table class="data-table" id="staffTable">
              <thead><tr><th>账号</th><th>姓名</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
              <tbody><tr><td colspan="5" class="pending-line">加载中…</td></tr></tbody>
            </table>
          </div>
        </div>`;

      view.querySelector('#stSaveDing').addEventListener('click', () => saveSection({
        dingtalk: {
          app_key: $('#stAppKey').value.trim(),
          app_secret: $('#stAppSecret').value.trim(),
          union_id: $('#stUnionId').value.trim(),
          app_uuid: $('#stAppUuid').value.trim(),
          sheet_id: $('#stSheetId').value.trim(),
        },
      }, '钉钉配置已保存'));
      view.querySelector('#stSaveProxy').addEventListener('click', () => saveSection({
        proxy: {
          proxy_host: $('#stProxyHost').value.trim(),
          proxy_port: Number($('#stProxyPort').value) || 0,
        },
      }, '代理配置已保存'));
      view.querySelector('#stSaveDir').addEventListener('click', () => saveSection({
        customOutputDir: $('#stOutputDir').value.trim(),
      }, '目录配置已保存'));
      view.querySelector('#nuCreate').addEventListener('click', createStaff);
    },
    onShow() {
      const u = App.state.user;
      $('#stAccount').innerHTML = u
        ? `当前账号：<b>${esc(u.username || '')}</b>（${esc(u.role || '')}）<br>会话：<code>${esc(App.state.sessionId)}</code>`
        : '未登录';

      // 员工管理：仅管理员可见
      const isAdmin = String(u?.role || '').toUpperCase() === 'ADMIN';
      $('#staffSection').classList.toggle('hidden', !isAdmin);
      if (isAdmin && !App.state.demoMode) loadStaff();

      if (App.state.demoMode) return;
      window.YurenApi.getSettings().then((d) => {
        if (d.dingtalk) {
          $('#stAppKey').value = d.dingtalk.app_key || '';
          $('#stAppSecret').value = d.dingtalk.app_secret || '';
          $('#stUnionId').value = d.dingtalk.union_id || '';
          $('#stAppUuid').value = d.dingtalk.app_uuid || '';
          $('#stSheetId').value = d.dingtalk.sheet_id || '';
        }
        if (d.proxy) {
          $('#stProxyHost').value = d.proxy.proxy_host || '';
          $('#stProxyPort').value = d.proxy.proxy_port || '';
        }
        $('#stOutputDir').value = d.customOutputDir || '';
      }).catch(() => { /* 非管理员或接口失败时保持空 */ });
    },
  });

  async function saveSection(body, okMsg) {
    try {
      const d = await window.YurenApi.saveSettings(body);
      if (d.error || d.success === false) throw new Error(d.error || '保存失败');
      App.toast(okMsg);
    } catch (e) { App.toast('保存失败：' + e.message); }
  }

  /* ==================== 员工管理（管理员） ==================== */

  async function loadStaff() {
    const tbody = document.querySelector('#staffTable tbody');
    try {
      const d = await window.YurenApi.listUsers();
      const items = d.items || [];
      if (!items.length) {
        tbody.innerHTML = '<tr><td colspan="5" class="pending-line">暂无员工</td></tr>';
        return;
      }
      const me = App.state.user || {};
      tbody.innerHTML = items.map((u) => `
        <tr>
          <td>${esc(u.username)}</td>
          <td>${esc(u.displayName || '—')}</td>
          <td>${u.role === 'ADMIN' ? '<span class="tag-ok">管理员</span>' : '员工'}</td>
          <td>${u.enabled ? '<span class="tag-ok">在职</span>' : '<span class="tag-off">已停用</span>'}</td>
          <td>
            ${u.id === me.id ? '<span class="muted">当前账号</span>' : `
              <a href="javascript:;" data-staff-credit="${u.id}" data-name="${esc(u.username)}">充值点数</a> ·
              <a href="javascript:;" data-staff-reset="${u.id}" data-name="${esc(u.username)}">重置密码</a> ·
              <a href="javascript:;" data-staff-toggle="${u.id}" data-enabled="${u.enabled}">${u.enabled ? '停用' : '启用'}</a>`}
          </td>
        </tr>`).join('');
    } catch (e) {
      tbody.innerHTML = `<tr><td colspan="5" class="pending-line error">加载失败：${esc(e.message)}</td></tr>`;
    }
  }

  async function createStaff() {
    const username = $('#nuUsername').value.trim();
    const password = $('#nuPassword').value;
    if (!username || !password) { App.toast('请填写登录账号和初始密码'); return; }
    try {
      const d = await window.YurenApi.createUser({
        username,
        password,
        displayName: $('#nuDisplayName').value.trim(),
        role: $('#nuRole').value,
      });
      if (d.success === false || d.error) throw new Error(d.error || '创建失败');
      App.toast(`员工 ${username} 已纳入`);
      $('#nuUsername').value = '';
      $('#nuDisplayName').value = '';
      $('#nuPassword').value = '';
      loadStaff();
    } catch (e) { App.toast('创建失败：' + e.message); }
  }

  // 员工表格行内操作（事件委托）
  document.addEventListener('click', async (e) => {
    const creditBtn = e.target.closest('[data-staff-credit]');
    const resetBtn = e.target.closest('[data-staff-reset]');
    const toggleBtn = e.target.closest('[data-staff-toggle]');

    if (creditBtn) {
      const input = prompt(`给 ${creditBtn.dataset.name} 充值点数（输入正整数）：`, '1000');
      if (input === null) return;
      const points = parseInt(input, 10);
      if (!points || points <= 0) { App.toast('点数必须是正整数'); return; }
      try {
        const d = await window.YurenApi.adminCredit(Number(creditBtn.dataset.staffCredit), points, '管理员分配');
        if (d.success === false || d.error) throw new Error(d.error || '充值失败');
        App.toast(`已为 ${creditBtn.dataset.name} 充值 ${points.toLocaleString()} 点`);
      } catch (err) { App.toast('充值失败：' + err.message); }
      return;
    }

    if (resetBtn) {
      const pwd = prompt(`为 ${resetBtn.dataset.name} 设置新密码：`);
      if (pwd === null) return;
      if (!pwd.trim()) { App.toast('密码不能为空'); return; }
      try {
        const d = await window.YurenApi.resetUserPassword(Number(resetBtn.dataset.staffReset), pwd.trim());
        if (d.success === false || d.error) throw new Error(d.error || '重置失败');
        App.toast('密码已重置');
      } catch (err) { App.toast('重置失败：' + err.message); }
      return;
    }

    if (toggleBtn) {
      const enable = toggleBtn.dataset.enabled !== 'true';
      if (!enable && !confirm('停用后该员工将无法登录，确认停用？')) return;
      try {
        const d = await window.YurenApi.setUserEnabled(Number(toggleBtn.dataset.staffToggle), enable);
        if (d.success === false || d.error) throw new Error(d.error || '操作失败');
        App.toast(enable ? '已启用' : '已停用');
        loadStaff();
      } catch (err) { App.toast('操作失败：' + err.message); }
    }
  });
})();
