# 自定义模式纯白底直出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让自定义模式在“原图延展 + 跳过 AI 分析 + 关闭画面文案 + 明确白底要求”时只发送纯白底商品图约束。

**Architecture:** 新增 `frontend/js/custom-direct-prompt.js`，导出白底意图识别和直接生成 Prompt 构造函数。`frontend/index.html` 只读取界面状态并调用该模块，分析确认链路保持不变。

**Tech Stack:** 原生 JavaScript、浏览器全局模块、Node.js `assert` 回归测试。

---

### Task 1: 锁定 Prompt 构造行为

**Files:**
- Modify: `tools/test-frontend-session-modules.js`
- Test: `tools/test-frontend-session-modules.js`

- [ ] **Step 1: 编写失败测试**

新增 `testCustomDirectPrompt()`，验证纯白底条件成立时输出包含产品一致性、纯白背景、自然接触阴影、无文字、无道具，并且不包含传入的默认风格 Prompt 和类目 Prompt；同时验证风格不是 `original` 或未关闭画面文案时沿用旧拼接结果。

- [ ] **Step 2: 运行测试确认失败**

Run: `node tools/test-frontend-session-modules.js`

Expected: FAIL，因为 `frontend/js/custom-direct-prompt.js` 尚不存在或未导出 `AiStudioCustomDirectPrompt`。

### Task 2: 实现独立 Prompt 构造模块

**Files:**
- Create: `frontend/js/custom-direct-prompt.js`
- Modify: `frontend/index.html`

- [ ] **Step 1: 实现白底意图识别**

识别“白底图”“纯白背景”“白色背景”“白底商品图”“白底产品图”等明确表达，不把普通“白色产品”误判为白底意图。

- [ ] **Step 2: 实现直接生成 Prompt 构造器**

函数接收 `userPrompt`、`styleKey`、`stylePrompt`、`categoryPrompt`、`skipAnalysis`、`withText`。仅在全部条件成立时返回用户输入加固定纯白底约束，否则返回原有三段拼接结果。

- [ ] **Step 3: 接入页面提交分支**

在 `frontend/index.html` 引入模块，并在 `/api/custom_generate` 的直接提交分支中调用构造器。纯白底分支不附加类目负向 Prompt，其他分支保持现状。

- [ ] **Step 4: 运行前端模块测试**

Run: `node tools/test-frontend-session-modules.js`

Expected: PASS，输出 `frontend session modules tests passed`。

### Task 3: 完整回归验证

**Files:**
- Verify: `frontend/index.html`
- Verify: `frontend/js/custom-direct-prompt.js`
- Verify: `tools/test-frontend-session-modules.js`

- [ ] **Step 1: 运行画布拖拽回归**

Run: `node tools/check-canvas-chat-drag.js`

Expected: PASS。

- [ ] **Step 2: 运行服务冒烟测试**

Run: `node tools/check-server-smoke.js`

Expected: PASS。

- [ ] **Step 3: 运行 Maven 测试**

Run: `mvn test`

Expected: 所有测试通过，无 failure 和 error。
