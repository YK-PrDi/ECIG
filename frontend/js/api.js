/**
 * 羽刃新前端 — API 客户端（按后端真实契约封装）
 * 与后端同源部署时直接生效（Spring Boot 端口 5020）。
 * 独立预览（file:// 或其他端口）时，所有请求会失败并进入「演示模式」。
 */
(function () {
  const BASE = ''; // 同源；如需跨域调试改为 'http://localhost:5020'

  async function readJson(resp) {
    const text = await resp.text();
    let data;
    try { data = JSON.parse(text); }
    catch { throw new Error('服务返回了非 JSON 内容（可能未登录或服务未启动）'); }
    return data;
  }

  async function getJson(url) { return readJson(await fetch(BASE + url)); }

  async function postJson(url, body) {
    return readJson(await fetch(BASE + url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }));
  }

  async function postForm(url, formData) {
    return readJson(await fetch(BASE + url, { method: 'POST', body: formData }));
  }

  async function putJson(url, body) {
    return readJson(await fetch(BASE + url, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }));
  }

  async function del(url) {
    return readJson(await fetch(BASE + url, { method: 'DELETE' }));
  }

  const api = {
    /* ---------- 认证 ---------- */
    login: (username, password) => postJson('/api/auth/login', { username, password }),
    checkAuth: () => getJson('/api/auth/check'),
    /** 公开配置状态 -> {dingtalkConfigured, registrationEnabled} */
    configStatus: () => getJson('/api/config/status'),
    /** 自助注册 {username, password, displayName?, enterpriseName?} -> {success, user}（开关关闭时 403） */
    register: (body) => postJson('/api/auth/register', body),

    /* ---------- 企业（多租户） ---------- */
    /** 中控看全部（含人数/点数统计）；负责人只看本企业 */
    listEnterprises: () => getJson('/api/enterprises'),
    /** 中控 {name, ownerUsername?, ownerPassword?, ownerDisplayName?}；无企业用户 {name} 自助建企业 */
    createEnterprise: (body) => postJson('/api/enterprises', body),
    assignEnterpriseOwner: (id, userId) => putJson('/api/enterprises/' + encodeURIComponent(id) + '/owner', { userId }),

    /* ---------- 会话 / 模型 ---------- */
    currentSession: () => getJson('/api/session/current'),
    listAgents: () => getJson('/api/agents'),

    /* ---------- 开品模式 ---------- */
    /** multipart -> {fields: [{key, value}]} */
    async kaipinAnalyze(formData) {
      const resp = await fetch(BASE + '/api/kaipin_analyze', { method: 'POST', body: formData });
      const data = await readJson(resp);
      if (!resp.ok || data.error) throw new Error(data.error || '分析失败（HTTP ' + resp.status + '）');
      return data;
    },

    /* ---------- 电商模式 ---------- */
    /** -> {products: [{id, name, category, main_count, sku_count}]} */
    listProducts: () => getJson('/api/products'),
    /** 标准生图 JSON {productIds, agentId, prompt, sessionId, aspect...} -> {taskId} */
    generate: (body) => postJson('/api/generate', body),
    /** 自定义/电商生图 FormData -> {taskId, output_dir} */
    customGenerate: (formData) => postForm('/api/custom_generate', formData),
    /** 自定义分析 FormData(images, prompt, count, withText) -> {text} */
    customAnalyze: (formData) => postForm('/api/custom_analyze', formData),
    /** 局部重绘 FormData(image, mask, prompt, sessionId) -> {results: [...], thought} */
    inpaint: (formData) => postForm('/api/inpaint', formData),

    /* ---------- 任务轮询 ---------- */
    /** -> {taskId, status, progress, total, currentProduct, results: [{name, status, message?, output?, localPath?}]} */
    taskStatus: (taskId) => getJson('/api/task/' + encodeURIComponent(taskId)),
    stopTask: (taskId) => postForm('/api/task/' + encodeURIComponent(taskId) + '/stop', new FormData()),

    /* ---------- 视频 ---------- */
    /** -> [{id, name, providerId, provider, level, inputMode, configured}] */
    videoModels: () => getJson('/api/video/models'),
    /** FormData(model, prompt, aspectRatio, durationSeconds, sessionId, images?) -> {taskId} */
    videoGenerate: (formData) => postForm('/api/video/generate', formData),
    /** 视频文件 URL（直接可作 <video src>） */
    videoFileUrl: (filename) => BASE + '/api/video/file?filename=' + encodeURIComponent(filename),

    /* ---------- 历史 ---------- */
    historyGenerations: (params = {}) =>
      getJson('/api/history/generations?' + new URLSearchParams(params)),
    historyThumbnailUrl: (id) => BASE + '/api/history/thumbnail?id=' + encodeURIComponent(id),

    /* ---------- 素材库 ---------- */
    kaipinMaterials: (limit = 120) => getJson('/api/kaipin_materials?limit=' + limit),
    addKaipinMaterial: (formData) => postForm('/api/kaipin_materials', formData),
    deleteKaipinMaterial: (id) =>
      readJson(fetch(BASE + '/api/kaipin_materials/' + encodeURIComponent(id), { method: 'DELETE' })),
    kaipinMaterialGenerate: (formData) => postForm('/api/kaipin_material_generate', formData),

    /* ---------- 图库 ---------- */
    gallery: (path) => getJson('/api/gallery' + (path ? '?path=' + encodeURIComponent(path) : '')),
    saveToGallery: (tempPath, subDir) => postJson('/api/save-to-gallery', { tempPath, subDir }),

    /* ---------- 设置 ---------- */
    getSettings: () => getJson('/api/settings'),
    saveSettings: (body) => postJson('/api/settings', body),

    /* ---------- 企业资产库 ---------- */
    /** -> {items: [{id, type, title, sourceMode, uploaderId, uploaderName, createdAt, url, downloadUrl}], totalElements...} */
    assets: (params = {}) => getJson('/api/assets?' + new URLSearchParams(params)),
    /** {sourcePath, title?, type?, sourceMode?} -> {success, item} */
    publishAsset: (body) => postJson('/api/assets', body),
    deleteAsset: (id) => del('/api/assets/' + encodeURIComponent(id)),
    assetFileUrl: (id) => BASE + '/api/assets/' + encodeURIComponent(id) + '/file',
    assetDownloadUrl: (id) => BASE + '/api/assets/' + encodeURIComponent(id) + '/download',

    /* ---------- 员工管理（管理员） ---------- */
    /** -> {items: [{id, username, displayName, role, enabled, createdAt}], total} */
    listUsers: () => getJson('/api/users'),
    createUser: (body) => postJson('/api/users', body),
    resetUserPassword: (id, password) => putJson('/api/users/' + encodeURIComponent(id) + '/password', { password }),
    setUserEnabled: (id, enabled) => putJson('/api/users/' + encodeURIComponent(id) + '/enabled', { enabled }),

    /* ---------- 计费 ---------- */
    /** -> {userId, balancePoints, frozenPoints, availablePoints} */
    wallet: () => getJson('/api/billing/wallet'),
    /** 管理员给员工充值点数 -> {success, ledger} */
    adminCredit: (userId, points, remark) =>
      postJson('/api/billing/admin/credit', { userId, points, remark }),
  };

  window.YurenApi = api;
})();
