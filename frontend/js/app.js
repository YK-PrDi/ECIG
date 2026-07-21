/**
 * 羽刃新前端 — SPA 壳
 * 职责：登录认证、侧边导航视图切换、全局共享工具（App.*）。
 * 各模式逻辑在 js/views/*.js，通过 App.registerView(name, {init, onShow}) 挂载。
 */
(function () {
  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => Array.from(document.querySelectorAll(sel));

  const App = {
    $, $$,
    state: {
      user: null,
      sessionId: 'sess_default',
      demoMode: false,   // 后端不可达时为 true
      agents: [],        // /api/agents 缓存
      currentView: 'kaipin',
    },

    escapeHtml(s) {
      return String(s).replace(/[&<>"']/g, (c) =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    },

    /** 全局轻提示 */
    toast(msg, ms = 2600) {
      const el = $('#toast');
      el.textContent = msg;
      el.classList.remove('hidden');
      clearTimeout(this._toastTimer);
      this._toastTimer = setTimeout(() => el.classList.add('hidden'), ms);
    },

    /** 图片地址规范化：http(s) 直用，否则走 /api/image?path= */
    toImgUrl(p) {
      if (!p) return '';
      if (/^https?:\/\//i.test(p) || p.startsWith('blob:') || p.startsWith('data:')) return p;
      return '/api/image?path=' + encodeURIComponent(p);
    },

    /**
     * 流程链指示器：在容器内渲染/更新「步骤链」，让用户随时知道走到哪一步。
     * @param {string|Element} target 容器（选择器或元素）
     * @param {string[]} steps 步骤名
     * @param {number} current 当前进行中的步骤下标；>= steps.length 表示全部完成
     */
    flowChain(target, steps, current) {
      const el = typeof target === 'string' ? $(target) : target;
      if (!el) return;
      el.classList.add('flow-chain');
      el.innerHTML = steps.map((label, i) => {
        const cls = i < current ? 'done' : (i === current ? 'active' : '');
        const dot = i < current ? '✓' : String(i + 1);
        const line = i < steps.length - 1
          ? `<span class="fc-line${i < current ? ' done' : ''}"></span>` : '';
        return `<span class="fc-step ${cls}"><span class="fc-dot">${dot}</span>${this.escapeHtml(label)}</span>${line}`;
      }).join('');
    },

    _views: {},
    /** 视图模块注册：App.registerView('ecommerce', { init(view) {}, onShow(view) {} }) */
    registerView(name, mod) { this._views[name] = mod || {}; },

    /** 切换视图（带淡入动效）；无专属视图的导航走 wip 占位 */
    gotoView(name) {
      // 平台中控账号只有中控视图，无生成入口（权责隔离）
      if (this.state.user && String(this.state.user.role).toUpperCase() === 'SUPERADMIN' && name !== 'platform') {
        name = 'platform';
      }
      const WIP = {
        projects: ['项目中心', '项目编组与交付物管理正在规划中'],
        goods: ['商品中心', '商品库管理将在电商模式数据打通后开放'],
        review: ['审核中心', '人工审核流将在全流程执行上线后开放'],
        dashboard: ['数据看板', '用量与产出数据看板将在计费数据积累后开放'],
      };

      let target = name;
      if (WIP[name]) {
        $('#wipTitle').textContent = WIP[name][0];
        $('#wipDesc').textContent = WIP[name][1];
        target = 'wip';
      }
      if (!document.getElementById('view-' + target)) target = 'kaipin';

      $$('.view').forEach((v) => v.classList.add('hidden'));
      const view = document.getElementById('view-' + target);
      view.classList.remove('hidden');
      view.classList.remove('view-enter');
      void view.offsetWidth;
      view.classList.add('view-enter');

      $$('#sidebarNav .nav-item').forEach((n) =>
        n.classList.toggle('active', n.dataset.view === name));

      this.state.currentView = name;

      // 惰性初始化视图模块
      const mod = this._views[target];
      if (mod && mod.init && !mod._inited) {
        mod._inited = true;
        mod.init(view);
      }
      if (mod && mod.onShow) mod.onShow(view);
      this.enhanceAllSelects();
    },

    /**
     * 拖拽上传：让元素成为文件拖放区（高亮反馈 + drop 回调）。
     * @param {string|Element} target 拖放区（选择器或元素）
     * @param {(files: File[]) => void} onFiles 收到文件的回调
     */
    enableDropUpload(target, onFiles) {
      const el = typeof target === 'string' ? $(target) : target;
      if (!el) return;
      let depth = 0; // 处理子元素间的 enter/leave 抖动
      el.addEventListener('dragenter', (e) => {
        e.preventDefault();
        depth++;
        el.classList.add('drop-hover');
      });
      el.addEventListener('dragover', (e) => e.preventDefault());
      el.addEventListener('dragleave', (e) => {
        e.preventDefault();
        if (--depth <= 0) { depth = 0; el.classList.remove('drop-hover'); }
      });
      el.addEventListener('drop', (e) => {
        e.preventDefault();
        depth = 0;
        el.classList.remove('drop-hover');
        const files = Array.from(e.dataTransfer?.files || []).filter((f) => f.type.startsWith('image/'));
        if (!files.length) { this.toast('只支持拖入图片文件'); return; }
        onFiles(files);
      });
    },

    /**
     * 结果卡片「存入企业库」：POST /api/assets
     * type='video' 时 rawPath 传视频 filename（后端按产出目录解析）。
     * btn 为按钮元素，成功后变为「已入库」。
     */
    async publishToCompany(btn, rawPath, title, type, sourceMode) {
      if (this.state.demoMode) { this.toast('演示模式：未连接后端，无法入库'); return; }
      if (!rawPath) { this.toast('缺少文件路径，无法入库'); return; }
      btn.disabled = true;
      try {
        const body = (type === 'video')
          ? { videoFilename: rawPath, title: title || '', type: 'video', sourceMode: sourceMode || 'video' }
          : { sourcePath: rawPath, title: title || '', type: 'image', sourceMode: sourceMode || '' };
        const r = await window.YurenApi.publishAsset(body);
        if (r.success === false || r.error) throw new Error(r.error || '入库失败');
        btn.textContent = '✓ 已入库';
        this.toast('已存入企业资产库');
      } catch (e) {
        btn.disabled = false;
        this.toast('入库失败：' + e.message);
      }
    },

    /**
     * 把原生 <select> 升级为配套样式的自定义下拉。
     * 原控件保留（.value / change 事件不变），只隐藏显示层。
     * 选项动态变化（如 agents 列表刷新）时面板自动重建。
     */
    enhanceSelect(sel) {
      if (!sel || sel.dataset.xEnhanced) return;
      sel.dataset.xEnhanced = '1';
      const wrap = document.createElement('div');
      wrap.className = 'x-select';
      sel.parentNode.insertBefore(wrap, sel);
      wrap.appendChild(sel);

      const trigger = document.createElement('button');
      trigger.type = 'button';
      trigger.className = 'x-select-trigger';
      trigger.innerHTML = '<span class="x-select-label"></span><span class="x-select-caret">▾</span>';
      const panel = document.createElement('div');
      panel.className = 'x-select-panel hidden';
      wrap.appendChild(trigger);
      wrap.appendChild(panel);

      const rebuild = () => {
        panel.innerHTML = Array.from(sel.options).map((o) => `
          <div class="x-select-option ${o.value === sel.value ? 'active' : ''}" data-value="${this.escapeHtml(o.value)}">
            <span class="xo-text">${this.escapeHtml(o.textContent)}</span>
          </div>`).join('');
        const current = sel.options[sel.selectedIndex];
        trigger.querySelector('.x-select-label').textContent = current ? current.textContent : '—';
      };
      rebuild();
      // 选项被 JS 重写（如模型列表刷新）时重建面板
      new MutationObserver(rebuild).observe(sel, { childList: true });

      trigger.addEventListener('click', (e) => {
        e.stopPropagation();
        const willOpen = !wrap.classList.contains('open');
        App.$$('.x-select.open').forEach((x) => { x.classList.remove('open'); x.querySelector('.x-select-panel').classList.add('hidden'); });
        if (willOpen) {
          wrap.classList.add('open');
          panel.classList.remove('hidden');
          const active = panel.querySelector('.x-select-option.active');
          if (active) active.scrollIntoView({ block: 'nearest' });
        }
      });
      panel.addEventListener('click', (e) => {
        const opt = e.target.closest('.x-select-option');
        if (!opt) return;
        sel.value = opt.dataset.value;
        sel.dispatchEvent(new Event('change', { bubbles: true }));
        rebuild();
        wrap.classList.remove('open');
        panel.classList.add('hidden');
      });
    },

    /** 升级页面内所有原生 select（视图初始化后调用） */
    enhanceAllSelects() {
      this.$$('select').forEach((sel) => this.enhanceSelect(sel));
    },

    /**
     * 通用任务轮询（契约：GET /api/task/{id} 每 800ms）
     * callbacks: onProgress(data, newResults), onResult(result), onDone(data)
     * 返回 stop 函数。
     */    pollTask(taskId, callbacks = {}) {
      let seen = 0;
      let stopped = false;
      const timer = setInterval(async () => {
        if (stopped) return;
        let data;
        try { data = await window.YurenApi.taskStatus(taskId); }
        catch { return; } // 单次失败继续等下一轮
        const results = Array.isArray(data.results) ? data.results : [];
        const fresh = results.slice(seen);
        seen = results.length;
        const freshSuccess = fresh.filter((r) => r.status === 'success' && !String(r.name || '').startsWith('__'));
        if (callbacks.onProgress) callbacks.onProgress(data, fresh);
        freshSuccess.forEach((r) => callbacks.onResult && callbacks.onResult(r));
        if (!['running', 'pending', 'stopping'].includes(data.status)) {
          stopped = true;
          clearInterval(timer);
          if (callbacks.onDone) callbacks.onDone(data);
        }
      }, 800);
      return () => { stopped = true; clearInterval(timer); };
    },
  };
  window.App = App;

  /* ================= 登录 ================= */

  async function initAuth() {
    // 注册开关：后端关闭时隐藏注册入口（模块化，开关打开即恢复）
    window.YurenApi.configStatus().then((s) => {
      if (s && s.registrationEnabled === false) {
        const link = $('#toRegister');
        if (link) link.closest('.login-switch').classList.add('hidden');
      }
    }).catch(() => { /* 接口不可达时保持默认显示 */ });
    try {
      const res = await window.YurenApi.checkAuth();
      if (res.authenticated && res.user) {
        setUser(res.user);
      } else {
        showLogin();
      }
      await initSession();
      loadAgents();
      loadWallet();
      afterLoginNav();
    } catch {
      App.state.demoMode = true;
      setUser({ username: '演示用户', role: '演示模式（后端未连接）' });
    }
  }

  /** 顶栏「企业可用点数」：接 GET /api/billing/wallet */
  async function loadWallet() {
    try {
      const w = await window.YurenApi.wallet();
      if (w && w.availablePoints != null) {
        $('#pointsValue').textContent = Number(w.availablePoints).toLocaleString('zh-CN');
      }
    } catch { /* 计费未启用或接口失败时保持占位 */ }
  }
  App.refreshWallet = loadWallet;

  function setUser(user) {
    App.state.user = user;
    $('#userName').textContent = user.username || user.name || '用户';
    $('#userRole').textContent = roleLabel(user.role);
    $('#userAvatar').textContent = String(user.username || '用').charAt(0);
    applyRoleNav(user.role);
    hideLogin();
  }

  function roleLabel(role) {
    const r = String(role || '').toUpperCase();
    if (r === 'SUPERADMIN') return '平台中控';
    if (r === 'ADMIN') return '企业负责人';
    return '企业成员';
  }

  /** 按角色过滤导航：中控只见「平台中控」，其余账号不见中控入口 */
  function applyRoleNav(role) {
    const isSA = String(role || '').toUpperCase() === 'SUPERADMIN';
    $('#navPlatform').classList.toggle('hidden', !isSA);
    App.$$('#sidebarNav .nav-item').forEach((n) => {
      if (n.id === 'navPlatform') return;
      n.classList.toggle('hidden', isSA);
    });
  }

  function showLogin() { $('#loginOverlay').classList.remove('hidden'); }
  function hideLogin() { $('#loginOverlay').classList.add('hidden'); }

  $('#loginBtn').addEventListener('click', doLogin);
  $('#loginPass').addEventListener('keydown', (e) => { if (e.key === 'Enter') doLogin(); });

  /* ---------- 注册 ---------- */
  $('#toRegister').addEventListener('click', () => {
    $('#loginForm').classList.add('hidden');
    $('#registerForm').classList.remove('hidden');
  });
  $('#toLogin').addEventListener('click', () => {
    $('#registerForm').classList.add('hidden');
    $('#loginForm').classList.remove('hidden');
  });
  $('#registerBtn').addEventListener('click', doRegister);

  async function doRegister() {
    const errEl = $('#registerError');
    errEl.classList.add('hidden');
    const username = $('#regUser').value.trim();
    const password = $('#regPass').value;
    if (!username || !password) {
      errEl.textContent = '账号和密码必填';
      errEl.classList.remove('hidden');
      return;
    }
    try {
      const res = await window.YurenApi.register({
        username,
        password,
        displayName: $('#regDisplayName').value.trim(),
        enterpriseName: $('#regEnterprise').value.trim(),
      });
      if (res.success) {
        App.toast(res.user.enterpriseId ? '注册成功，你已是企业负责人' : '注册成功');
        setUser(res.user);
        await initSession();
        loadAgents();
        loadWallet();
        App.gotoView('workspace');
      } else {
        errEl.textContent = res.error || '注册失败';
        errEl.classList.remove('hidden');
      }
    } catch (e) {
      errEl.textContent = '无法连接后端：' + e.message;
      errEl.classList.remove('hidden');
    }
  }

  function afterLoginNav() {
    const isSA = String(App.state.user?.role || '').toUpperCase() === 'SUPERADMIN';
    App.gotoView(isSA ? 'platform' : App.state.currentView === 'platform' ? 'workspace' : App.state.currentView);
  }

  async function doLogin() {
    const errEl = $('#loginError');
    errEl.classList.add('hidden');
    try {
      const res = await window.YurenApi.login($('#loginUser').value.trim(), $('#loginPass').value);
      if (res.success) {
        setUser(res.user);
        await initSession();
        loadAgents();
        loadWallet();
        afterLoginNav();
      } else {
        errEl.textContent = res.error || '登录失败';
        errEl.classList.remove('hidden');
      }
    } catch (e) {
      errEl.textContent = '无法连接后端：' + e.message;
      errEl.classList.remove('hidden');
    }
  }

  /** 当前工作会话 ID（生成/历史归档用） */
  async function initSession() {
    try {
      const data = await window.YurenApi.currentSession();
      if (data && data.sessionId) App.state.sessionId = data.sessionId;
    } catch { /* 保留默认 sess_default */ }
  }

  /** 生图模型清单缓存（加载完成后广播，各视图下拉自动刷新） */
  async function loadAgents() {
    try {
      const list = await window.YurenApi.listAgents();
      if (Array.isArray(list) && list.length) {
        App.state.agents = list;
        document.dispatchEvent(new CustomEvent('agents:loaded'));
      }
    } catch { /* 忽略，各视图自行兜底 */ }
  }

  /* ================= 导航 ================= */

  $('#sidebarNav').addEventListener('click', (e) => {
    const item = e.target.closest('.nav-item');
    if (!item) return;
    App.gotoView(item.dataset.view);
  });

  $('#navCollapse').addEventListener('click', () => {
    const sb = $('#sidebar');
    sb.classList.toggle('collapsed');
    $('#navCollapse').textContent = sb.classList.contains('collapsed') ? '»' : '« 收起导航';
  });

  /* ================= 退出登录 ================= */
  $('#logoutBtn').addEventListener('click', async () => {
    if (!confirm('确认退出登录？')) return;
    try { await fetch('/api/auth/logout', { method: 'POST' }); } catch { /* 忽略 */ }
    location.reload();
  });

  /* ================= 全局委托：结果卡片「存入企业库」 ================= */
  document.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-publish-asset]');
    if (!btn) return;
    App.publishToCompany(
      btn,
      btn.getAttribute('data-publish-asset'),
      btn.getAttribute('data-title') || '',
      btn.getAttribute('data-type') || 'image',
      btn.getAttribute('data-mode') || '');
  });

  /* ================= 全局委托：点击空白处关闭所有下拉 ================= */
  document.addEventListener('click', () => {
    App.$$('.x-select.open').forEach((x) => {
      x.classList.remove('open');
      x.querySelector('.x-select-panel').classList.add('hidden');
    });
  });

  /* ================= 启动 ================= */
  initAuth();
  App.gotoView(App.state.currentView); // 初始化默认视图（触发惰性 init）
})();
