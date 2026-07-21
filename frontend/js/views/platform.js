/**
 * 平台中控视图（仅 SUPERADMIN）
 * 职责：建企业、指定企业负责人、监视各企业（人数/点数）、给负责人充值。
 * 按权责设计：中控看不到任何企业的生成内容与资产库。
 */
(function () {
  const App = window.App;
  const $ = App.$, esc = App.escapeHtml;

  const state = { users: [] };

  function init(view) {
    view.querySelector('#platformBody').innerHTML = `
      <div class="view-grid" id="pfStats">
        <div class="stat-card"><div class="stat-label">企业总数</div><div class="stat-num" id="pfEntCount">—</div></div>
        <div class="stat-card"><div class="stat-label">员工总数</div><div class="stat-num" id="pfMemberCount">—</div></div>
        <div class="stat-card"><div class="stat-label">全平台点数余额</div><div class="stat-num" id="pfPointsTotal">—</div></div>
        <div class="stat-card"><div class="stat-label">冻结中点数</div><div class="stat-num" id="pfFrozenTotal">—</div></div>
      </div>

      <div class="card" style="margin-top:16px">
        <div class="card-head"><b>新建企业</b><small class="muted">（可同时创建负责人账号，也可稍后指定）</small></div>
        <div class="mask-tools">
          <input id="peName" class="spec-input" placeholder="企业名称 *" style="max-width:200px">
          <input id="peOwnerUser" class="spec-input" placeholder="负责人账号（可选）" style="max-width:160px">
          <input id="peOwnerName" class="spec-input" placeholder="负责人姓名" style="max-width:130px">
          <input id="peOwnerPass" class="spec-input" type="password" placeholder="负责人初始密码" style="max-width:160px">
          <button class="btn btn-primary" id="peCreate">＋ 创建企业</button>
        </div>
      </div>

      <div class="card">
        <div class="card-head"><b>企业列表</b><small class="muted">（点击行内操作进行管理）</small>
          <button class="link-btn" id="pfReload">↻ 刷新</button></div>
        <table class="data-table" id="pfTable">
          <thead><tr>
            <th>企业名称</th><th>负责人</th><th>员工数</th>
            <th>点数余额</th><th>冻结中</th><th>创建时间</th><th>操作</th>
          </tr></thead>
          <tbody><tr><td colspan="7" class="pending-line">加载中…</td></tr></tbody>
        </table>
      </div>`;

    view.querySelector('#peCreate').addEventListener('click', createEnterprise);
    view.querySelector('#pfReload').addEventListener('click', loadAll);

    // 行内操作委托
    view.querySelector('#pfTable').addEventListener('click', async (e) => {
      const assignBtn = e.target.closest('[data-pf-assign]');
      const creditBtn = e.target.closest('[data-pf-credit]');
      if (assignBtn) await assignOwner(assignBtn.dataset.pfAssign, assignBtn.dataset.name);
      if (creditBtn) await creditOwner(creditBtn.dataset.pfCredit, creditBtn.dataset.ownerId, creditBtn.dataset.name);
    });
  }

  async function loadAll() {
    if (App.state.demoMode) {
      renderDemo();
      return;
    }
    try {
      const [entRes, userRes] = await Promise.all([
        window.YurenApi.listEnterprises(),
        window.YurenApi.listUsers().catch(() => ({ items: [] })),
      ]);
      state.users = userRes.items || [];
      renderEnterprises(entRes.items || []);
    } catch (e) {
      $('#pfTable tbody').innerHTML = `<tr><td colspan="7" class="pending-line error">加载失败：${esc(e.message)}</td></tr>`;
    }
  }

  function renderEnterprises(items) {
    $('#pfEntCount').textContent = items.length;
    $('#pfMemberCount').textContent = items.reduce((s, it) => s + (it.memberCount || 0), 0);
    $('#pfPointsTotal').textContent = items.reduce((s, it) => s + (it.totalBalancePoints || 0), 0).toLocaleString('zh-CN');
    $('#pfFrozenTotal').textContent = items.reduce((s, it) => s + (it.totalFrozenPoints || 0), 0).toLocaleString('zh-CN');

    const tbody = $('#pfTable tbody');
    if (!items.length) {
      tbody.innerHTML = '<tr><td colspan="7" class="pending-line">还没有企业，先在上方创建一个</td></tr>';
      return;
    }
    tbody.innerHTML = items.map((it) => `
      <tr>
        <td><b>${esc(it.name)}</b></td>
        <td>${it.ownerName ? esc(it.ownerName) : '<span class="tag-off">未指定</span>'}</td>
        <td>${it.memberCount ?? 0}</td>
        <td>${Number(it.totalBalancePoints || 0).toLocaleString('zh-CN')}</td>
        <td>${Number(it.totalFrozenPoints || 0).toLocaleString('zh-CN')}</td>
        <td>${esc(String(it.createdAt || '').slice(0, 10))}</td>
        <td>
          <a href="javascript:;" data-pf-assign="${it.id}" data-name="${esc(it.name)}">指定负责人</a> ·
          <a href="javascript:;" data-pf-credit="${it.id}" data-owner-id="${it.ownerId || ''}" data-name="${esc(it.name)}">充值点数</a>
        </td>
      </tr>`).join('');
  }

  async function createEnterprise() {
    const name = $('#peName').value.trim();
    if (!name) { App.toast('请填写企业名称'); return; }
    const ownerUsername = $('#peOwnerUser').value.trim();
    if (ownerUsername && !$('#peOwnerPass').value) {
      App.toast('创建负责人账号时需要设置初始密码');
      return;
    }
    try {
      const d = await window.YurenApi.createEnterprise({
        name,
        ownerUsername: ownerUsername || undefined,
        ownerPassword: $('#peOwnerPass').value || undefined,
        ownerDisplayName: $('#peOwnerName').value.trim() || undefined,
      });
      if (d.success === false || d.error) throw new Error(d.error || '创建失败');
      App.toast(`企业「${name}」已创建`);
      $('#peName').value = '';
      $('#peOwnerUser').value = '';
      $('#peOwnerName').value = '';
      $('#peOwnerPass').value = '';
      loadAll();
    } catch (e) { App.toast('创建失败：' + e.message); }
  }

  async function assignOwner(enterpriseId, enterpriseName) {
    if (!state.users.length) {
      try { state.users = (await window.YurenApi.listUsers()).items || []; } catch { /* ignore */ }
    }
    const candidates = state.users.filter((u) => u.role !== 'SUPERADMIN');
    const menu = candidates.map((u) => `${u.id}: ${u.username}（${u.displayName || '—'}）`).join('\n');
    const input = prompt(`为「${enterpriseName}」指定负责人，输入用户 ID：\n\n${menu || '（暂无可选用户，可先在新建企业时创建负责人账号）'}`);
    if (input === null) return;
    const userId = parseInt(input, 10);
    if (!userId) { App.toast('请输入有效的用户 ID'); return; }
    try {
      const d = await window.YurenApi.assignEnterpriseOwner(Number(enterpriseId), userId);
      if (d.success === false || d.error) throw new Error(d.error || '指定失败');
      App.toast('负责人已指定');
      loadAll();
    } catch (e) { App.toast('指定失败：' + e.message); }
  }

  async function creditOwner(enterpriseId, ownerId, enterpriseName) {
    if (!ownerId) { App.toast('该企业还没有负责人，请先指定'); return; }
    const input = prompt(`给「${enterpriseName}」的负责人充值点数（负责人再分配给成员）：`, '10000');
    if (input === null) return;
    const points = parseInt(input, 10);
    if (!points || points <= 0) { App.toast('点数必须是正整数'); return; }
    try {
      const d = await window.YurenApi.adminCredit(Number(ownerId), points, '平台充值-' + enterpriseName);
      if (d.success === false || d.error) throw new Error(d.error || '充值失败');
      App.toast(`已充值 ${points.toLocaleString()} 点`);
      loadAll();
    } catch (e) { App.toast('充值失败：' + e.message); }
  }

  function renderDemo() {
    renderEnterprises([
      { id: 1, name: '羽刃科技（默认企业）', ownerName: 'Admin 用户', memberCount: 5, totalBalancePoints: 2450060, totalFrozenPoints: 0, createdAt: '2026-07-10' },
      { id: 2, name: '示例电商公司', ownerName: '未指定', memberCount: 0, totalBalancePoints: 0, totalFrozenPoints: 0, createdAt: '2026-07-21' },
    ]);
  }

  App.registerView('platform', { init, onShow: loadAll });
})();
