/**
 * 开品模式视图 — 外观创新设计工作台
 * 接通 /api/kaipin_analyze（真实契约），演示模式下使用内置示例数据。
 */
(function () {
  const App = window.App;
  const $ = App.$, $$ = App.$$, esc = App.escapeHtml;

  const state = { imageA: null, imageB: null, analyzing: false };

  /* ================= 分析流水线（阶段化动效） ================= */
  const PIPELINE = [
    { step: 2, title: '语言解析', at: 0,    text: '上传产品素材，建立分析上下文' },
    { step: 2, title: '语言解析', at: 900,  text: '解析产品结构与外观维度' },
    { step: 2, title: '语言解析', at: 2100, text: '提炼设计关键词与约束条件' },
    { step: 3, title: '方案输出', at: 3400, text: '生成多维设计方案（材质 / 配色 / 结构 / 逻辑）' },
    { step: 3, title: '方案输出', at: 5200, text: '组织方案结构，等待模型返回' },
  ];

  let pipelineTimers = [];
  let elapsedTimer = null;

  function startPipeline() {
    stopPipeline();
    $('#outputEmpty').classList.add('hidden');
    $('#outputContent').classList.add('hidden');
    $('#outputLoading').classList.remove('hidden');
    $('#stageLog').innerHTML = '';
    gotoStep(2);

    const t0 = Date.now();
    elapsedTimer = setInterval(() => {
      $('#loadingElapsed').textContent = ((Date.now() - t0) / 1000).toFixed(1);
    }, 100);

    PIPELINE.forEach((stage) => {
      pipelineTimers.push(setTimeout(() => pushStage(stage), stage.at));
    });
  }

  function pushStage(stage) {
    gotoStep(stage.step);
    $('#loadingTitle').textContent = stage.title + '中';
    const log = $('#stageLog');
    const prev = log.querySelector('li.active');
    if (prev) {
      prev.classList.replace('active', 'done');
      prev.querySelector('.log-ico').textContent = '✓';
    }
    const li = document.createElement('li');
    li.className = 'active';
    li.innerHTML = '<span class="log-ico"><i></i><i></i><i></i></span>' + esc(stage.text);
    log.appendChild(li);
  }

  function finishPipeline() {
    stopPipeline();
    const prev = $('#stageLog').querySelector('li.active');
    if (prev) {
      prev.classList.replace('active', 'done');
      prev.querySelector('.log-ico').textContent = '✓';
    }
  }

  function stopPipeline() {
    pipelineTimers.forEach(clearTimeout);
    pipelineTimers = [];
    if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; }
  }

  /* ================= 交互绑定 ================= */

  function bindUpload(boxId, key) {
    const box = $(boxId);
    const input = box.querySelector('input[type=file]');
    box.addEventListener('click', () => input.click());
    input.addEventListener('change', () => {
      if (input.files[0]) applyFile(input.files[0]);
      input.value = '';
    });
    // 拖拽上传：拖到哪个框就进哪个框（取第一张）
    App.enableDropUpload(box, (files) => applyFile(files[0]));

    function applyFile(file) {
      if (!file) return;
      state[key] = file;
      const url = URL.createObjectURL(file);
      box.style.backgroundImage = `url(${url})`;
      box.classList.add('has-img');
      const preview = key === 'imageA' ? $('#imgProductA') : $('#imgProductB');
      preview.style.backgroundImage = `url(${url})`;
      preview.classList.add('has-img');
      preview.textContent = '';
      App.toast('图片已添加');
    }
  }

  function init() {
    bindUpload('#uploadA', 'imageA');
    bindUpload('#uploadB', 'imageB');

    $('#stepsBar').addEventListener('click', (e) => {
      const step = e.target.closest('.step');
      if (!step) return;
      $$('#stepsBar .step').forEach((s) => s.classList.remove('active'));
      step.classList.add('active');
    });

    $$('#view-kaipin .collapse-toggle').forEach((btn) =>
      btn.addEventListener('click', () => btn.closest('.card').classList.toggle('open')));

    ['#chipSelling', '#chipStyle'].forEach((sel) =>
      $(sel).addEventListener('click', (e) => {
        if (e.target.classList.contains('chip')) e.target.classList.toggle('on');
      }));

    $('#btnAnalyze').addEventListener('click', runAnalyze);
    $('#btnSaveDraft').addEventListener('click', () => App.toast('草稿已保存（暂未持久化）'));
    $('#btnNewTask').addEventListener('click', () => location.reload());
    $('#btnSwitchModel').addEventListener('click', () => App.gotoView('models'));
  }

  function selectedChips(sel) {
    return $$(sel + ' .chip.on').map((c) => c.textContent.trim());
  }

  function buildFormData() {
    const fd = new FormData();
    if (state.imageA) fd.append('imageA', state.imageA);
    if (state.imageB) fd.append('imageB', state.imageB);

    const specLines = [
      $('#inpProductName').value.trim(),
      $('#inpModel').value.trim() && '型号: ' + $('#inpModel').value.trim(),
      $('#inpType').value.trim() && '类型: ' + $('#inpType').value.trim(),
      $('#inpSize').value.trim() && '尺寸: ' + $('#inpSize').value.trim(),
      $('#inpMaterial').value.trim() && '材质: ' + $('#inpMaterial').value.trim(),
    ].filter(Boolean).join('\n');
    fd.append('productA', specLines);
    fd.append('productB', $('#inpProductB').value.trim());

    const selling = [
      $('#inpGoal').value.trim(),
      '目标人群: ' + $('#selAudience').value,
      '使用场景: ' + $('#selScene').value,
      '核心卖点: ' + selectedChips('#chipSelling').join('、'),
      $('#inpBudget').value.trim() && '参考预算: ' + $('#inpBudget').value.trim(),
      '输出内容: ' + $$('#checkOutputs input:checked').map((c) => c.value).join('、'),
    ].filter(Boolean).join('\n');
    fd.append('selling', selling);

    fd.append('focus', 'premium');
    const styles = $$('#chipStyle .chip.on').map((c) => c.dataset.style).filter(Boolean);
    fd.append('style', styles[0] || '');
    if (styles.length) fd.append('styleText', selectedChips('#chipStyle').join('、'));
    return fd;
  }

  async function runAnalyze() {
    if (state.analyzing) return;
    const hasA = state.imageA || $('#inpProductName').value.trim();
    if (!hasA) {
      setStatus('请先上传产品图 A，或填写产品名称用于分析', true);
      return;
    }

    state.analyzing = true;
    const btn = $('#btnAnalyze');
    btn.disabled = true;
    btn.classList.add('loading');
    btn.textContent = '分析中…';
    setStatus('正在调用 /api/kaipin_analyze 进行外观分析…');
    startPipeline();

    const startedAt = Date.now();
    try {
      let fields;
      if (App.state.demoMode) {
        await new Promise((r) => setTimeout(r, 4200)); // 演示模式：让阶段动效完整播放
        fields = demoFields();
      } else {
        const data = await window.YurenApi.kaipinAnalyze(buildFormData());
        fields = (data.fields || []).map((f) => ({
          key: String(f.key || '').trim(),
          value: String(f.value || '').trim(),
        })).filter((f) => f.key && f.value);
      }

      if (!fields.length) throw new Error('分析结果为空，请补充产品信息后重试');

      finishPipeline();
      // 遮罩短暂停留让「完成」状态可见，立即撤下
      await new Promise((r) => setTimeout(r, 250));
      $('#outputLoading').classList.add('hidden');

      renderOutput(fields);
      gotoStep(3);
      const secs = ((Date.now() - startedAt) / 1000).toFixed(0);
      $('#stDuration').textContent = secs + ' 秒';
      $('#stTaskId').textContent = 'T' + new Date().toISOString().slice(0, 10).replaceAll('-', '') + '-0001';
      $('#stCreated').textContent = new Date().toLocaleString('zh-CN', { hour12: false });
      $('#outputMeta').textContent = `（生成时间 ${secs}s）`;
      setStatus('分析完成，已生成 ' + fields.length + ' 个设计维度');
      $('#autosaveTip').textContent = '☁ 自动保存于 ' + new Date().toTimeString().slice(0, 5);
      $('#btnRunAll').disabled = false;
      $('#outputPanel').scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (e) {
      stopPipeline();
      $('#outputLoading').classList.add('hidden');
      $('#outputEmpty').classList.remove('hidden');
      setStatus('分析失败：' + e.message, true);
      gotoStep(1);
    } finally {
      state.analyzing = false;
      btn.disabled = false;
      btn.classList.remove('loading');
      btn.textContent = '⟳ 开始分析 / 重新生成';
    }
  }

  function gotoStep(n) {
    $$('#stepsBar .step').forEach((s) => {
      const no = Number(s.dataset.step);
      s.classList.toggle('active', no === n);
      s.classList.toggle('done', no < n);
    });
  }

  function setStatus(msg, isError) {
    const el = $('#analyzeStatus');
    el.textContent = msg;
    el.classList.toggle('error', !!isError);
    el.classList.remove('hidden');
  }

  /* ================= 输出渲染 ================= */

  const SECTION_RULES = [
    { title: '材质与工艺', match: /材质|工艺|表面|电镀|材料/ },
    { title: '配色方案', match: /配色|颜色|色彩/ },
    { title: '结构说明', match: /结构|接口|装配|组件/ },
    { title: '设计逻辑与价值', match: /逻辑|价值|定位|人群|卖点/ },
  ];
  const CHECK_RE = /校验|检查|可制造|合规|防水|强度/;
  const RISK_RE = /风险|预警|问题|避免|注意/;

  function renderOutput(fields) {
    $('#outputEmpty').classList.add('hidden');
    $('#outputContent').classList.remove('hidden');

    const checkItems = [];
    const riskItems = [];
    const buckets = new Map(SECTION_RULES.map((r) => [r.title, []]));
    const others = [];

    for (const f of fields) {
      const lines = f.value.split(/\n+/).map((s) => s.trim()).filter(Boolean);
      if (CHECK_RE.test(f.key)) { checkItems.push(...lines); continue; }
      if (RISK_RE.test(f.key)) { riskItems.push(...lines); continue; }
      const rule = SECTION_RULES.find((r) => r.match.test(f.key));
      if (rule) buckets.get(rule.title).push(f.value);
      else others.push(f);
    }

    const container = $('#analysisCards');
    container.innerHTML = '';
    for (const [title, values] of buckets) {
      if (values.length) container.appendChild(fieldCard(title, values));
    }
    for (const f of others) {
      container.appendChild(fieldCard(f.key, [f.value]));
    }

    renderList('#manuCheckList', checkItems, 'ok', '本次分析未包含可制造性校验维度（待接入校验模型）');
    renderRiskList(riskItems);

    const goal = $('#inpGoal').value.trim();
    $('#refNotes').innerHTML = goal ? '<b>设计目标</b><br>' + esc(goal) : '';

    $$('#outputContent .card').forEach((card, i) => {
      card.classList.remove('reveal');
      void card.offsetWidth;
      card.style.animationDelay = (i * 90) + 'ms';
      card.classList.add('reveal');
    });
  }

  function fieldCard(title, values) {
    const card = document.createElement('div');
    card.className = 'card field-card';
    const items = values
      .flatMap((v) => v.split(/\n+/).map((s) => s.trim()).filter(Boolean))
      .map((line) => '<li>' + esc(line.replace(/^[-•\d.、\s]+/, '')) + '</li>')
      .join('');
    card.innerHTML = '<div class="card-head"><b>' + esc(title) + '</b></div><ul>' + items + '</ul>';
    return card;
  }

  function renderList(sel, items, cls, emptyMsg) {
    const el = $(sel);
    if (!items.length) {
      el.innerHTML = '<li class="pending">' + esc(emptyMsg) + '</li>';
      return;
    }
    el.innerHTML = items.map((t) => '<li class="' + cls + '">' + esc(t) + '</li>').join('');
  }

  function renderRiskList(items) {
    const el = $('#riskList');
    if (!items.length) {
      el.innerHTML = '<li class="pending">本次分析未返回风险预警维度（待接入校验模型）</li>';
      return;
    }
    el.innerHTML = items.map((t) => '<li>' + esc(t) + '</li>').join('');
  }

  /** 演示模式数据 */
  function demoFields() {
    return [
      { key: '材质与工艺', value: '主体材质：ABS 工程塑料（耐高温）\n表面处理：多层电镀（Ni 8μm + Cr 0.25μm）\n出水面板：硅胶出水嘴（易清洁、防堵塞）\n切换按键：隐藏式侧边按键（POM）\n接口：G1/2 黄铜内牙（符合国标）\n工艺：注塑成型 + 电镀 + 组装' },
      { key: '配色方案', value: '电镀银（推荐）\n枪灰黑\n拉丝镍\n哑光白\n玫瑰金' },
      { key: '结构说明', value: '1. 硅胶出水面板（可拆洗）\n2. 面板固定环（卡扣结构）\n3. 内部导流盘（增压设计）\n4. 切换阀芯（三通）\n5. 手柄主体（ABS 电镀）\n6. 进水接头（G1/2 黄铜）\n7. 密封圈（防漏）' },
      { key: '设计逻辑与价值', value: '1. 设计语言：借鉴高端品牌的圆形面板与极简弧线，通过隐藏式按键提升整体纯净感\n2. 体验升级：增压节水出水盘，配合三种出水模式，兼顾舒适与节能\n3. 工艺与质感：多层电镀 + 精密组件，提升耐用性与高端感\n4. 差异化卖点：易清洁硅胶出水嘴 + 隐藏按键 + 健康防烫结构设计' },
      { key: '可制造性校验', value: '接口标准化检查：G1/2 接口符合国标，通用安装无障碍\n出水路径检查：内部导流路径连贯，无不合理折返或封闭\n结构强度检查：手柄壁厚 ≥ 2.5mm，关键受力区域加强筋设计合理\n防水密封检查：关键结合处设置 O 型圈，防水等级可达 IPX7' },
      { key: '风险与问题预警', value: '避免不可能结构：内置多根折流导致内部积水或无自排空风险\n避免不稳定几何：手柄弧面过薄或宽部可能影响强度与握持\n避免不可用结构：按键突出过高可能误触或磕碰，不利用户体验' },
    ];
  }

  /* ================= 注册视图 ================= */
  App.registerView('kaipin', { init });
})();
