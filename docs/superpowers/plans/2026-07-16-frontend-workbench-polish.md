# 羽刃创作工作台精修 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保留现有三栏工作台与五种生成模式，完成银灰专业视觉精修、画布稳定性修复、可靠局部编辑和清晰的开品素材融合交互。

**Architecture:** 继续使用现有无框架单页结构和 Spring Boot 接口，不重写业务层。新增三个可独立测试的浏览器模块，分别负责工作台布局计算、局部编辑准备状态和开品素材选择映射；`index.html` 只负责 DOM 绑定与已有业务编排，`index.css` 统一视觉令牌和响应式规则。

**Tech Stack:** HTML5、CSS3、原生 JavaScript、Canvas 2D、Node.js `assert`/`vm` 测试、Spring Boot 3.3、Maven、Playwright。

---

## 文件结构

- Create: `frontend/js/workbench-layout.js`：面板宽度约束和画布相机中心保持的纯函数。
- Create: `frontend/js/inpaint-session.js`：局部编辑原图/蒙版异步汇合、蒙版有效性和提交状态纯函数。
- Create: `frontend/js/kaipin-material-selection.js`：把素材数据库记录、选择 ID 和本次提示词覆盖值整理成融合方案。
- Modify: `frontend/index.html`：语义化生成工具栏、局部编辑工具、开品组合预览和模块接线。
- Modify: `frontend/index.css`：银灰视觉令牌、控件密度、层级、边缘防冲突、动画与响应式规则。
- Modify: `tools/test-frontend-session-modules.js`：新增三个纯模块的回归测试和 HTML 接线契约。
- Create: `tools/check-workbench-layout.js`：四个视口的 Playwright 布局、重叠和截图检查。
- Test: `src/test/java/com/elebusiness/controller/GenerateControllerBillingAuditTest.java`：保留并补强局部编辑原图/蒙版转发验证。

### Task 1: 保存旧 UI 版本并建立前端契约测试

**Files:**
- Modify: `tools/test-frontend-session-modules.js`
- Reference: `frontend/index.html`
- Reference: `frontend/index.css`

- [ ] **Step 1: 为旧 UI 提交创建回退标签**

Run:

```powershell
git tag -a ui-before-workbench-polish-20260716 3aa43a8b8bec4139352ee6a70f9cf470f7c8808f -m "UI before workbench polish"
git show --no-patch --oneline ui-before-workbench-polish-20260716
```

Expected: 输出提交 `3aa43a8 chore: load optional local runtime environment`。

- [ ] **Step 2: 在现有前端模块测试中加入新接线契约，先观察失败**

在 `testIndexHtmlWiresExternalModules()` 中增加：

```javascript
assert.match(html, /<script src="js\/workbench-layout\.js"><\/script>/);
assert.match(html, /<script src="js\/inpaint-session\.js"><\/script>/);
assert.match(html, /<script src="js\/kaipin-material-selection\.js"><\/script>/);
assert.match(html, /class="generation-toolbar"/);
assert.match(html, /id="rectMaskBtn"/);
assert.match(html, /id="undoMaskBtn"/);
assert.match(html, /id="redoMaskBtn"/);
assert.match(html, /id="kpMaterialSelectionSummary"/);
```

- [ ] **Step 3: 运行测试确认因模块和 DOM 尚不存在而失败**

Run:

```powershell
node tools/test-frontend-session-modules.js
```

Expected: FAIL，首个失败为缺少 `workbench-layout.js` 接线。

- [ ] **Step 4: 提交测试基线**

```powershell
git add tools/test-frontend-session-modules.js
git commit -m "test: define polished workbench UI contract"
```

### Task 2: 建立银灰视觉系统并整理生成对话区

**Files:**
- Modify: `frontend/index.css:1-250`
- Modify: `frontend/index.css:1650-2050`
- Modify: `frontend/index.html:106-170`
- Modify: `frontend/index.html:610-680`
- Test: `tools/test-frontend-session-modules.js`

- [ ] **Step 1: 在 `:root` 和深色主题中建立最小视觉令牌**

用以下令牌替换现有渐变背景、超大圆角和分散层级值：

```css
:root {
    --bg-workspace: #edf0f4;
    --bg-panel: #ffffff;
    --bg-card: #f8fafc;
    --surface-raised: #ffffff;
    --text-main: #172033;
    --text-muted: #5f6b7a;
    --text-dim: #8a96a6;
    --border: #dce2ea;
    --border-strong: #c8d0dc;
    --primary: #2563eb;
    --primary-hover: #1d4ed8;
    --primary-light: #eaf1ff;
    --success: #16875d;
    --warning: #b7791f;
    --danger: #dc2626;
    --radius-sm: 4px;
    --radius-md: 6px;
    --radius-lg: 8px;
    --shadow-float: 0 12px 32px rgba(31, 42, 58, 0.12);
    --control-h: 36px;
    --topnav-h: 52px;
    --z-canvas: 1;
    --z-tools: 30;
    --z-menu: 160;
    --z-modal: 500;
    --z-blocking: 1000;
    --motion-fast: 130ms;
    --motion-panel: 180ms;
    --font: Inter, "Microsoft YaHei", system-ui, sans-serif;
}

body.theme-dark {
    --bg-workspace: #15191f;
    --bg-panel: #20252d;
    --bg-card: #191e25;
    --surface-raised: #252b34;
    --text-main: #edf2f7;
    --text-muted: #b8c0cc;
    --text-dim: #8893a2;
    --border: #343c48;
    --border-strong: #45505f;
    --primary: #4f8cff;
    --primary-hover: #73a2ff;
    --primary-light: rgba(79, 140, 255, 0.14);
}
```

- [ ] **Step 2: 移除全局慢动画和装饰动画**

删除全局 `0.6s !important` 颜色过渡、彩虹背景动画、按钮上移、预览缩放和拖拽弹跳。替换为：

```css
button, input, textarea, .gf-select-header, .mode-btn {
    transition: color var(--motion-fast) ease,
                background-color var(--motion-fast) ease,
                border-color var(--motion-fast) ease,
                box-shadow var(--motion-fast) ease;
}

@media (prefers-reduced-motion: reduce) {
    *, *::before, *::after {
        scroll-behavior: auto !important;
        animation-duration: 0.01ms !important;
        animation-iteration-count: 1 !important;
        transition-duration: 0.01ms !important;
    }
}
```

- [ ] **Step 3: 把生成区顶部内联布局改为语义化工具栏**

将模式选择和模型选择外层替换为：

```html
<div class="generation-toolbar">
    <div class="mode-selector" role="tablist" aria-label="生成模式">
        <!-- 保留现有五个 mode-btn、ID 和 onclick -->
    </div>
    <div class="generation-model-control">
        <span class="compact-label">AI 模型</span>
        <div class="gf-select sm drop-up" id="agentSelectWrapper">
            <!-- 保留现有选择器 DOM -->
        </div>
    </div>
</div>
```

对应 CSS：

```css
.generation-toolbar {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    gap: 12px;
    margin-bottom: 10px;
    min-width: 0;
}
.mode-selector {
    display: flex;
    gap: 3px;
    min-width: 0;
    overflow-x: auto;
    scrollbar-width: none;
}
.mode-selector::-webkit-scrollbar { display: none; }
.mode-btn {
    min-height: 32px;
    padding: 0 10px;
    border-radius: var(--radius-md);
    white-space: nowrap;
    flex: 0 0 auto;
}
.generation-model-control {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
}
.compact-label { font-size: 12px; color: var(--text-muted); white-space: nowrap; }
```

- [ ] **Step 4: 统一核心控件密度与留白**

```css
.input-field,
.gf-select-header,
.btn-primary,
.btn-outline {
    min-height: var(--control-h);
    border-radius: var(--radius-md);
    font-size: 13px;
}
.config-group { min-width: 0; gap: 5px; }
.config-group label {
    font-size: 12px;
    font-weight: 500;
    letter-spacing: 0;
    text-transform: none;
    color: var(--text-muted);
}
.input-container { padding: 12px; border-radius: var(--radius-lg); }
```

- [ ] **Step 5: 增加边缘防冲突和中窄屏规则**

```css
.app-content, .workspace, .chat-panel, .canvas-panel,
.input-area-wrapper, .input-container, .config-drawer { min-width: 0; }
.gf-select-options { z-index: var(--z-menu); max-width: min(360px, calc(100vw - 24px)); }
.resizer { flex: 0 0 5px; width: 5px; background: transparent; position: relative; }
.resizer::after { content: ""; position: absolute; inset: 0 2px; background: var(--border); }
.resizer:hover::after, .resizer.dragging::after { background: var(--primary); }

@media (max-width: 1180px) {
    .sidebar { width: 168px; }
    .compact-label { display: none; }
    .generation-toolbar { gap: 8px; }
}
@media (max-width: 900px) {
    .generation-toolbar { grid-template-columns: 1fr; }
    .generation-model-control { justify-content: flex-end; }
}
```

- [ ] **Step 6: 运行契约测试并提交**

Run:

```powershell
node tools/test-frontend-session-modules.js
```

Expected: 仍因尚未创建三个 JS 模块和局部编辑 DOM 而失败，但 `generation-toolbar` 契约通过。

```powershell
git add frontend/index.html frontend/index.css
git commit -m "style: refine workbench visual hierarchy"
```

### Task 3: 修复画布 resize 跳动和面板宽度越界

**Files:**
- Create: `frontend/js/workbench-layout.js`
- Modify: `frontend/index.html:728-734`
- Modify: `frontend/index.html:7885-7895`
- Modify: `frontend/index.html:8050-8115`
- Modify: `tools/test-frontend-session-modules.js`

- [ ] **Step 1: 为布局纯函数增加失败测试**

在测试文件增加：

```javascript
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
```

并在主执行函数中调用 `testWorkbenchLayout()`。

- [ ] **Step 2: 运行测试确认模块缺失**

Run: `node tools/test-frontend-session-modules.js`

Expected: FAIL with `ENOENT frontend/js/workbench-layout.js`。

- [ ] **Step 3: 创建布局模块**

```javascript
(function (global) {
    'use strict';

    function clampPanelWidth(value, min, max) {
        const width = Number.isFinite(Number(value)) ? Number(value) : min;
        return Math.min(max, Math.max(min, width));
    }

    function preserveCameraCenter(camera, scale, oldSize, newSize) {
        if (!oldSize.width || !oldSize.height || !scale) return { ...camera };
        const worldX = (oldSize.width / 2 - camera.x) / scale;
        const worldY = (oldSize.height / 2 - camera.y) / scale;
        return {
            x: newSize.width / 2 - worldX * scale,
            y: newSize.height / 2 - worldY * scale
        };
    }

    global.AiStudioWorkbenchLayout = { clampPanelWidth, preserveCameraCenter };
})(window);
```

- [ ] **Step 4: 在 HTML 中先于画布代码加载模块**

```html
<script src="js/workbench-layout.js"></script>
<script src="js/canvas-selection.js"></script>
```

- [ ] **Step 5: 修改 CanvasBoard._resize 保持视觉中心**

```javascript
_resize() {
    const panel = document.getElementById('canvasPanel');
    const oldSize = { width: this.canvas.width, height: this.canvas.height };
    const newSize = { width: panel.clientWidth, height: panel.clientHeight };
    if (!newSize.width || !newSize.height) return;
    this.cam = AiStudioWorkbenchLayout.preserveCameraCenter(
        this.cam, this.scale, oldSize, newSize
    );
    this.canvas.width = newSize.width;
    this.canvas.height = newSize.height;
}
```

- [ ] **Step 6: 在 resizer 中统一宽度约束**

把 `initResizers` 的恢复和拖动计算替换为以下规则：

```javascript
const limits = side === 'left'
    ? { min: 148, max: 320 }
    : { min: 320, max: 520 };
const savedWidth = AiStudioWorkbenchLayout.clampPanelWidth(
    parseInt(localStorage.getItem(storageKey) || defaultWidth, 10),
    limits.min,
    limits.max
);
target.style.width = `${savedWidth}px`;

// mousedown
resizer.classList.add('dragging');

// mousemove
const dx = event.clientX - startX;
const candidate = startWidth + (side === 'left' ? dx : -dx);
const canvasWidth = document.getElementById('canvasPanel').getBoundingClientRect().width;
const dynamicMax = Math.min(limits.max, candidate + Math.max(0, canvasWidth - 360));
const nextWidth = AiStudioWorkbenchLayout.clampPanelWidth(candidate, limits.min, dynamicMax);
target.style.width = `${nextWidth}px`;

// mouseup
resizer.classList.remove('dragging');
localStorage.setItem(storageKey, String(parseInt(target.style.width, 10)));
```

- [ ] **Step 7: 运行测试并提交**

Run: `node tools/test-frontend-session-modules.js`

Expected: 布局模块测试通过，后续缺失模块测试继续失败。

```powershell
git add frontend/js/workbench-layout.js frontend/index.html tools/test-frontend-session-modules.js
git commit -m "fix: stabilize canvas and panel resizing"
```

### Task 4: 可靠准备局部编辑请求并补全可见工具

**Files:**
- Create: `frontend/js/inpaint-session.js`
- Modify: `frontend/index.html:213-238`
- Modify: `frontend/index.html:7060-7720`
- Modify: `frontend/index.html:7895-8030`
- Modify: `frontend/index.css:2400-2700`
- Modify: `tools/test-frontend-session-modules.js`
- Modify: `src/test/java/com/elebusiness/controller/GenerateControllerBillingAuditTest.java`

- [ ] **Step 1: 增加局部编辑状态失败测试**

```javascript
async function testInpaintSession() {
  const sandbox = loadBrowserScript('frontend/js/inpaint-session.js');
  const inpaint = sandbox.AiStudioInpaintSession;
  assert.ok(inpaint, '局部编辑状态工具应导出 AiStudioInpaintSession');
  assert.strictEqual(inpaint.hasEditablePixels(new Uint8ClampedArray([0, 0, 0, 255])), false);
  assert.strictEqual(inpaint.hasEditablePixels(new Uint8ClampedArray([0, 0, 0, 0])), true);

  const image = { name: 'product.png' };
  const mask = { size: 128, type: 'image/png' };
  const ready = await inpaint.prepare(Promise.resolve(image), Promise.resolve(mask));
  assert.strictEqual(ready.imageFile, image);
  assert.strictEqual(ready.maskBlob, mask);
  await assert.rejects(
    () => inpaint.prepare(Promise.resolve(image), Promise.resolve({ size: 0 })),
    /蒙版/
  );
}
```

- [ ] **Step 2: 运行测试确认模块缺失**

Run: `node tools/test-frontend-session-modules.js`

Expected: FAIL with `ENOENT frontend/js/inpaint-session.js`。

- [ ] **Step 3: 创建局部编辑状态模块**

```javascript
(function (global) {
    'use strict';

    function hasEditablePixels(pixelData) {
        for (let index = 3; index < pixelData.length; index += 4) {
            if (pixelData[index] < 250) return true;
        }
        return false;
    }

    async function prepare(imagePromise, maskPromise) {
        const [imageFile, maskBlob] = await Promise.all([imagePromise, maskPromise]);
        if (!imageFile) throw new Error('原图尚未准备完成');
        if (!maskBlob || !maskBlob.size) throw new Error('蒙版为空，请先圈选修改区域');
        return { imageFile, maskBlob };
    }

    global.AiStudioInpaintSession = { hasEditablePixels, prepare };
})(window);
```

- [ ] **Step 4: 新增局部编辑工具按钮**

在现有画笔、橡皮和套索后增加：

```html
<button class="tool-btn" id="rectMaskBtn" onclick="setInpaintTool('rect')" title="矩形选区">
    <span>矩形</span>
</button>
<button class="tool-btn" id="undoMaskBtn" onclick="_boardProxy.undoMask()" title="撤销 Ctrl+Z">
    <span>撤销</span>
</button>
<button class="tool-btn" id="redoMaskBtn" onclick="_boardProxy.redoMask()" title="重做 Ctrl+Shift+Z">
    <span>重做</span>
</button>
<button class="tool-btn" id="toggleMaskBtn" onclick="_boardProxy.toggleMaskVisibility()" title="显示或隐藏蒙版">
    <span>预览</span>
</button>
```

增加状态容器：

```html
<div id="inpaintReadyState" class="inpaint-ready-state" aria-live="polite">
    请圈选需要修改的区域
</div>
```

- [ ] **Step 5: 在 CanvasBoard 中实现矩形、撤销、重做和蒙版显示**

初始化状态：

```javascript
this.redoStack = [];
this.rectStart = null;
this.maskVisible = true;
```

统一历史方法：

```javascript
_pushMaskHistory() {
    this.undoStack.push(this.maskCtx.getImageData(0, 0, this.maskCanvas.width, this.maskCanvas.height));
    if (this.undoStack.length > 20) this.undoStack.shift();
    this.redoStack = [];
}
undoMask() {
    if (!this.undoStack.length) return;
    this.redoStack.push(this.maskCtx.getImageData(0, 0, this.maskCanvas.width, this.maskCanvas.height));
    this.maskCtx.putImageData(this.undoStack.pop(), 0, 0);
    this._updateInpaintReadyState();
}
redoMask() {
    if (!this.redoStack.length) return;
    this.undoStack.push(this.maskCtx.getImageData(0, 0, this.maskCanvas.width, this.maskCanvas.height));
    this.maskCtx.putImageData(this.redoStack.pop(), 0, 0);
    this._updateInpaintReadyState();
}
toggleMaskVisibility() {
    this.maskVisible = !this.maskVisible;
    this.maskCanvas.style.opacity = this.maskVisible ? '1' : '0';
}
```

矩形工具使用以下三个方法，并分别从 `mousedown`、`mousemove` 和 `mouseup` 调用：

```javascript
_beginRectMask(point) {
    this._pushMaskHistory();
    this.rectStart = point;
    this.rectBaseSnapshot = this.maskCtx.getImageData(
        0, 0, this.maskCanvas.width, this.maskCanvas.height
    );
    this.drawing = true;
}
_previewRectMask(point) {
    if (!this.rectStart || !this.rectBaseSnapshot) return;
    this.maskCtx.putImageData(this.rectBaseSnapshot, 0, 0);
    this.maskCtx.fillStyle = 'rgba(255,80,80,0.55)';
    this.maskCtx.fillRect(
        this.rectStart.x,
        this.rectStart.y,
        point.x - this.rectStart.x,
        point.y - this.rectStart.y
    );
}
_finishRectMask(point) {
    if (!this.rectStart) return;
    this._previewRectMask(point);
    this.rectStart = null;
    this.rectBaseSnapshot = null;
    this.drawing = false;
    this._updateInpaintReadyState();
}
```

- [ ] **Step 6: 把原图和蒙版准备合并为一个 await 流程**

新增 Promise 包装：

```javascript
_exportMaskPromise(nodeId) {
    return new Promise((resolve, reject) => {
        this.exportMaskBlob(nodeId, blob => blob ? resolve(blob) : reject(new Error('蒙版导出失败')));
    });
}
_exportImageFilePromise(node) {
    return new Promise((resolve, reject) => {
        const naturalWidth = node.img.naturalWidth || node.w;
        const naturalHeight = node.img.naturalHeight || node.h;
        const target = 1024;
        const side = Math.max(naturalWidth, naturalHeight);
        const scale = target / side;
        const drawWidth = Math.round(naturalWidth * scale);
        const drawHeight = Math.round(naturalHeight * scale);
        const offsetX = Math.floor((target - drawWidth) / 2);
        const offsetY = Math.floor((target - drawHeight) / 2);
        const canvas = document.createElement('canvas');
        canvas.width = target;
        canvas.height = target;
        const context = canvas.getContext('2d');
        context.fillStyle = '#ffffff';
        context.fillRect(0, 0, target, target);
        context.drawImage(node.img, offsetX, offsetY, drawWidth, drawHeight);
        canvas.toBlob(blob => {
            if (!blob) return reject(new Error('原图导出失败'));
            resolve(new File([blob], 'canvas-inpaint-source.png', { type: 'image/png' }));
        }, 'image/png');
    });
}
async prepareSelectedForInpaint() {
    const id = this.editId || this.selectedId;
    const node = this._node(id);
    if (!node) throw new Error('请先选择图片');
    const ready = await AiStudioInpaintSession.prepare(
        this._exportImageFilePromise(node),
        this._exportMaskPromise(id)
    );
    window.pendingInpaint = ready;
    droppedFiles = [ready.imageFile];
    renderPreviews();
    document.getElementById('imagePreviewContainer').style.display = 'flex';
    this._updateInpaintReadyState('局部编辑已准备');
    return ready;
}
```

`executeTask()` 读取 `window.pendingInpaint.imageFile` 和 `window.pendingInpaint.maskBlob`。失败时不清理；仅生成成功后调用：

```javascript
function clearPendingInpaint() {
    window.pendingInpaint = null;
    window.pendingMask = null;
    droppedFiles = [];
    renderPreviews();
    document.getElementById('imagePreviewContainer').style.display = 'none';
}
```

- [ ] **Step 7: 统一局部编辑 JSON 错误解析**

把两处 `await res.json()` 改为：

```javascript
const result = await AiStudioApi.readJson(res, '局部编辑');
```

最终删除重复的 `submitInpaint()` 请求构造，保留一个 `submitPreparedInpaint(prompt)`。

- [ ] **Step 8: 补强后端测试**

在 `GenerateControllerBillingAuditTest` 的局部编辑测试中捕获传入代理的 `imageFile`、`maskFile` 和 `prompt`，断言两个文件存在且大于 0 字节、提示词保持原值、用户上下文正确。

- [ ] **Step 9: 运行测试并提交**

Run:

```powershell
node tools/test-frontend-session-modules.js
& 'E:\IDEA\IntelliJ IDEA Community Edition 2025.2.4\plugins\maven\lib\maven3\bin\mvn.cmd' '-f' 'F:\java\ele-business-java\pom.xml' '-Dtest=GenerateControllerBillingAuditTest' test
```

Expected: 两条命令均 PASS。

```powershell
git add frontend/js/inpaint-session.js frontend/index.html frontend/index.css tools/test-frontend-session-modules.js src/test/java/com/elebusiness/controller/GenerateControllerBillingAuditTest.java
git commit -m "feat: make canvas inpaint workflow reliable"
```

### Task 5: 明确开品素材与产品图的融合组合

**Files:**
- Create: `frontend/js/kaipin-material-selection.js`
- Modify: `frontend/index.html:560-610`
- Modify: `frontend/index.html:5700-5785`
- Modify: `frontend/index.html:3920-4010`
- Modify: `frontend/index.css:1100-1450`
- Modify: `tools/test-frontend-session-modules.js`

- [ ] **Step 1: 增加素材选择映射失败测试**

```javascript
function testKaiPinMaterialSelection() {
  const sandbox = loadBrowserScript('frontend/js/kaipin-material-selection.js');
  const selection = sandbox.AiStudioKaiPinSelection;
  assert.ok(selection, '开品素材选择工具应导出 AiStudioKaiPinSelection');
  const plans = selection.buildPlans(
    [{ id: 1, title: '折叠结构', prompt: '原始提示', imagePath: '/m1.png' }],
    ['1'],
    { 1: '本次覆盖提示' }
  );
  assert.deepStrictEqual(JSON.parse(JSON.stringify(plans)), [{
    id: '1', title: '折叠结构', prompt: '本次覆盖提示', imagePath: '/m1.png'
  }]);
}
```

- [ ] **Step 2: 运行测试确认模块缺失**

Run: `node tools/test-frontend-session-modules.js`

Expected: FAIL with `ENOENT frontend/js/kaipin-material-selection.js`。

- [ ] **Step 3: 创建素材选择模块**

```javascript
(function (global) {
    'use strict';

    function buildPlans(items, selectedIds, overrides) {
        const selected = new Set((selectedIds || []).map(String));
        const overrideMap = overrides || {};
        return (items || [])
            .filter(item => selected.has(String(item.id)))
            .map(item => ({
                id: String(item.id),
                title: item.title || '开品素材',
                prompt: String(overrideMap[item.id] || item.prompt || '').trim(),
                imagePath: item.imagePath || ''
            }));
    }

    global.AiStudioKaiPinSelection = { buildPlans };
})(window);
```

- [ ] **Step 4: 在开品配置中加入组合摘要容器**

```html
<div id="kpMaterialSelectionSummary" class="kp-material-selection-summary" aria-live="polite">
    <div class="kp-combination-empty">上传产品图并从素材库选择创意参考</div>
</div>
```

- [ ] **Step 5: 每次选择素材后渲染真实组合关系**

```javascript
function renderKaiPinMaterialSelectionSummary() {
    const host = document.getElementById('kpMaterialSelectionSummary');
    if (!host) return;
    const plans = AiStudioKaiPinSelection.buildPlans(
        window.kpMaterialDbItems,
        window.kpSelectedMaterialIds,
        window.kpMaterialPromptOverrides
    );
    if (!window.kpRefA || !plans.length) {
        host.innerHTML = '<div class="kp-combination-empty">上传产品图并从素材库选择创意参考</div>';
        return;
    }
    host.innerHTML = plans.map((plan, index) => `
        <article class="kp-combination-card">
            <div class="kp-combination-role"><strong>主体</strong><span>我的产品图</span></div>
            <span class="kp-combination-plus">+</span>
            <div class="kp-combination-role"><strong>创意 ${index + 1}</strong><span>${escHtml(plan.title)}</span></div>
            <p>${escHtml(plan.prompt || '使用素材图片本身作为创意参考')}</p>
        </article>
    `).join('');
}
```

在 `toggleKaiPinMaterialPick`、`cacheKaiPinMaterialPromptOverride`、`kpRenderThumb('A')` 和 `confirmKaiPinMaterialSelection` 后调用该函数。

- [ ] **Step 6: 强化生成前验证与数量提示**

生成按钮旁显示 `将生成 N 个独立方案`；提交前断言 `window.kpRefA` 存在且 `plans.length > 0`。请求继续发送 `whiteImage`、`materialIds` 和 `materialPromptOverrides`，不改变 `/api/kaipin_material_generate`。

- [ ] **Step 7: 运行测试并提交**

Run: `node tools/test-frontend-session-modules.js`

Expected: `frontend session modules tests passed`。

```powershell
git add frontend/js/kaipin-material-selection.js frontend/index.html frontend/index.css tools/test-frontend-session-modules.js
git commit -m "feat: clarify kaipin material fusion workflow"
```

### Task 6: Playwright 响应式、重叠与功能回归

**Files:**
- Create: `tools/check-workbench-layout.js`
- Modify: `tools/check-server-smoke.js`

- [ ] **Step 1: 创建四视口布局检查脚本**

创建完整脚本：

```javascript
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = (process.env.AI_STUDIO_BASE_URL || '').replace(/\/$/, '');
const username = process.env.AI_STUDIO_SMOKE_USERNAME || '';
const password = process.env.AI_STUDIO_SMOKE_PASSWORD || '';
if (!baseUrl || !username || !password) {
  throw new Error('缺少 AI_STUDIO_BASE_URL/AI_STUDIO_SMOKE_USERNAME/AI_STUDIO_SMOKE_PASSWORD');
}

const viewports = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'laptop', width: 1280, height: 720 },
  { name: 'tablet', width: 1024, height: 768 },
  { name: 'mobile', width: 390, height: 844 }
];

function overlaps(left, right) {
  if (!left || !right || left.width === 0 || right.width === 0) return false;
  return left.left < right.right && left.right > right.left &&
    left.top < right.bottom && left.bottom > right.top;
}

(async () => {
  const executablePath = path.join(
    __dirname, 'browsers', 'chromium_headless_shell-1223',
    'chrome-headless-shell-win64', 'chrome-headless-shell.exe'
  );
  const browser = await chromium.launch({
    headless: true,
    executablePath: fs.existsSync(executablePath) ? executablePath : undefined,
    args: ['--no-proxy-server']
  });
  const outputDir = path.resolve(__dirname, '..', 'target', 'workbench-screenshots');
  fs.mkdirSync(outputDir, { recursive: true });
  try {
    const context = await browser.newContext();
    const login = await context.request.post(`${baseUrl}/api/auth/login`, {
      data: { username, password }
    });
    if (!login.ok()) throw new Error(`登录失败：HTTP ${login.status()}`);

    for (const viewport of viewports) {
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      page.on('console', message => {
        if (message.type() === 'error') errors.push(message.text());
      });
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.goto(`${baseUrl}/index.html`, { waitUntil: 'networkidle', timeout: 30000 });
      await page.evaluate(() => setMode('custom'));
      await page.waitForTimeout(200);

      const geometry = await page.evaluate(() => {
        const rect = id => {
          const box = document.getElementById(id)?.getBoundingClientRect();
          return box ? {
            left: box.left, right: box.right, top: box.top, bottom: box.bottom,
            width: box.width, height: box.height
          } : null;
        };
        return {
          canvas: rect('canvasPanel'),
          chat: rect('chatPanel'),
          generate: rect('generateBtn'),
          agent: rect('agentSelectWrapper'),
          modeSelector: document.querySelector('.input-container .mode-selector')?.getBoundingClientRect().toJSON(),
          horizontalOverflow: document.documentElement.scrollWidth > innerWidth + 1
        };
      });

      if (geometry.horizontalOverflow) throw new Error(`${viewport.name} 存在页面级横向滚动`);
      if (!geometry.canvas || !geometry.chat) throw new Error(`${viewport.name} 缺少主区域`);
      if (viewport.width >= 1024 && (geometry.canvas.width <= 260 || geometry.chat.width <= 300)) {
        throw new Error(`${viewport.name} 主区域被过度压缩`);
      }
      if (viewport.width >= 1024 && overlaps(geometry.generate, geometry.agent)) {
        throw new Error(`${viewport.name} 发送按钮与模型选择器重叠`);
      }
      if (!geometry.modeSelector || geometry.modeSelector.width <= 0) {
        throw new Error(`${viewport.name} 模式选择器不可见`);
      }
      if (errors.length) throw new Error(`${viewport.name} 控制台错误：${errors.join('; ')}`);

      await page.screenshot({
        path: path.join(outputDir, `${viewport.name}.png`),
        fullPage: true
      });
      await page.close();
    }
    console.log(`workbench layout checks passed: ${viewports.length} viewports`);
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error.message);
  process.exit(1);
});
```

桌面和笔记本视口检查发送按钮与模型选择器不重叠；移动视口检查模式选择器可滚动且控件不超出视口。截图保存到 `target/workbench-screenshots/<name>.png`。

- [ ] **Step 2: 扩展服务器 smoke 的 UI 调用**

在现有 Playwright `page.evaluate` 中依次调用：

```javascript
setMode('custom');
setMode('ecommerce');
setMode('product');
openKaiPinMaterialDb();
closeKaiPinMaterialDb();
```

并断言页面控制台无错误、模式按钮始终只有一个 `.active`。

- [ ] **Step 3: 启动本地服务并执行浏览器测试**

Run:

```powershell
& 'E:\IDEA\IntelliJ IDEA Community Edition 2025.2.4\plugins\maven\lib\maven3\bin\mvn.cmd' '-f' 'F:\java\ele-business-java\pom.xml' package
$envFile = 'F:\java\ele-business-java\.env.local'
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $name = $matches[1]
        $value = $matches[2].Trim().Trim('"').Trim("'")
        Set-Item -Path "Env:$name" -Value $value
    }
}
$jar = 'F:\java\ele-business-java\target\ele-business-java-1.0.0.jar'
$log = 'F:\java\ele-business-java\target\workbench-smoke.log'
$errorLog = 'F:\java\ele-business-java\target\workbench-smoke-error.log'
$startArgs = @{
    FilePath = 'E:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot\bin\java.exe'
    ArgumentList = @('-jar', $jar, '--spring.profiles.active=prod,smoke', '--server.port=5021')
    RedirectStandardOutput = $log
    RedirectStandardError = $errorLog
    WindowStyle = 'Hidden'
    PassThru = $true
}
$process = Start-Process @startArgs
try {
    $ready = $false
    for ($i = 0; $i -lt 45; $i++) {
        try {
            Invoke-WebRequest -Uri 'http://127.0.0.1:5021/api/auth/check' -UseBasicParsing -TimeoutSec 2 | Out-Null
            $ready = $true
            break
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $ready) { throw "本地服务未在 45 秒内启动：$log" }
    $env:AI_STUDIO_BASE_URL = 'http://127.0.0.1:5021'
    $env:AI_STUDIO_SMOKE_USERNAME = $env:APP_USERNAME
    $env:AI_STUDIO_SMOKE_PASSWORD = $env:APP_PASSWORD
    node tools/check-server-smoke.js
    node tools/check-workbench-layout.js
} finally {
    if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force }
}
```

Expected: Maven 163+ tests全部通过；两个 Node smoke 脚本退出码为 0；四张截图均非空。

- [ ] **Step 4: 人工检查截图**

检查 `target/workbench-screenshots/`：

- 模式切换位于生成对话区顶部。
- 标签无挤压和遮挡。
- 银灰留白与面板边界一致。
- 画布在桌面、笔记本、平板视口均保留有效空间。
- 移动端无页面级横向滚动。

- [ ] **Step 5: 提交验证脚本**

```powershell
git add tools/check-workbench-layout.js tools/check-server-smoke.js
git commit -m "test: add responsive workbench smoke coverage"
```

### Task 7: 完成审计与最终交付

**Files:**
- Verify: `frontend/index.html`
- Verify: `frontend/index.css`
- Verify: `frontend/js/workbench-layout.js`
- Verify: `frontend/js/inpaint-session.js`
- Verify: `frontend/js/kaipin-material-selection.js`
- Verify: `tools/test-frontend-session-modules.js`
- Verify: `tools/check-workbench-layout.js`

- [ ] **Step 1: 运行全部验证**

```powershell
node tools/test-frontend-session-modules.js
& 'E:\IDEA\IntelliJ IDEA Community Edition 2025.2.4\plugins\maven\lib\maven3\bin\mvn.cmd' '-f' 'F:\java\ele-business-java\pom.xml' test
git diff --check
git status --short
```

Expected: Node 测试通过；Maven 0 failures/0 errors；`git diff --check` 无输出；工作区只保留用户原有未跟踪文件。

- [ ] **Step 2: 对照设计规格逐项检查**

确认以下证据齐全：

- 模式切换 DOM 位于 `.input-container .generation-toolbar`。
- CSS 中不存在核心控件的 `transition: all`、bounce 或 hover 位移。
- `CanvasBoard._resize` 使用 `preserveCameraCenter`。
- 局部编辑使用 `AiStudioInpaintSession.prepare` 汇合原图和蒙版。
- 局部编辑失败不清空待提交状态。
- 开品摘要由 `AiStudioKaiPinSelection.buildPlans` 生成。
- Playwright 四视口截图和无重叠断言通过。

- [ ] **Step 3: 生成最终提交**

```powershell
git add frontend/index.html frontend/index.css frontend/js/workbench-layout.js frontend/js/inpaint-session.js frontend/js/kaipin-material-selection.js tools/test-frontend-session-modules.js tools/check-workbench-layout.js tools/check-server-smoke.js src/test/java/com/elebusiness/controller/GenerateControllerBillingAuditTest.java
git commit -m "feat: polish creative workbench experience"
```

如果前面每个任务已经分别提交且无额外改动，则跳过空提交。
