const fs = require('fs');
const path = require('path');

const html = fs.readFileSync(path.join(__dirname, '..', 'frontend', 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(__dirname, '..', 'frontend', 'index.css'), 'utf8');
const kpMaterialController = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'elebusiness', 'controller', 'KaiPinMaterialController.java'),
  'utf8'
);
const generateController = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'elebusiness', 'controller', 'GenerateController.java'),
  'utf8'
);
const imageGenerationService = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'elebusiness', 'service', 'ImageGenerationService.java'),
  'utf8'
);

function fail(message) {
  console.error(message);
  process.exitCode = 1;
}

const standardMatch = html.match(/<div id="standardConfig"[\s\S]*?<div id="customConfig"/);
if (!standardMatch) {
  fail('standardConfig block not found');
} else {
  const standard = standardMatch[0];
  if (standard.includes('std-bg-panel')) {
    fail('standardConfig should not render the background style grid directly');
  }
  if (/id="grp-aspect"[^>]*display\s*:\s*none/.test(standard)) {
    fail('standard aspect selector should be visible');
  }
  if (/id="grp-negative-prompt"[^>]*display\s*:\s*none/.test(standard)) {
    fail('standard negative prompt should be visible');
  }
}

if (html.includes('未提交生图') || html.includes('return;\n            }\n\n            let segments;')) {
  fail('custom mode should fall back to editable generation prompts when Gemini analysis fails');
}

if (!html.includes('function resolveImageAgentId(agentId)') ||
    !html.includes('const agentId = resolveImageAgentId(selectedAgentId);') ||
    !html.includes("formData.set('agentId', finalAgentId)") ||
    !generateController.includes('String requestedAgentId = "gemini".equalsIgnoreCase(agentId) ? "gpt-image" : agentId')) {
  fail('custom mode should resolve Gemini analysis model to a real image generation agent before submitting');
}

if (!html.includes('function parseCustomPromptPoints') ||
    !html.includes('function parseCustomAnalysisBundle') ||
    !html.includes('function renderCustomSummaryEditor') ||
    !html.includes('function renderCustomPointEditor') ||
    !html.includes('function addCustomPromptPoint') ||
    !html.includes('function collectCustomPromptCards') ||
    !html.includes('doubao-summary-card') ||
    !html.includes('doubao-extra-points') ||
    !html.includes('doubao-add-point') ||
    !css.includes('.doubao-summary-card') ||
    !css.includes('.doubao-extra-points') ||
    !css.includes('.doubao-point-card') ||
    !css.includes('.doubao-add-point')) {
  fail('custom Gemini analysis should split every prompt point into editable cards and allow user-defined points');
}

if (!imageGenerationService.includes('【总分析】') ||
    !imageGenerationService.includes('【系列连续性】') ||
    !imageGenerationService.includes('【第 1 张方案】') ||
    !html.includes('collectCustomSummaryPrompt') ||
    !html.includes('总分析') ||
    !html.includes('系列连续性')) {
  fail('custom Gemini analysis should produce one shared summary and continuous per-image plans');
}

if (!html.includes("const selectedAgentId = params.agentId || document.getElementById('agentSelect')?.value") ||
    !html.includes('const agentId = resolveImageAgentId(selectedAgentId);') ||
    !html.includes("formData.append('agentId', finalAgentId)") ||
    !kpMaterialController.includes('String generationAgentId = "gemini".equalsIgnoreCase(agentId) ? "gpt-image" : agentId') ||
    !kpMaterialController.includes('generationAgentId,')) {
  fail('kaipin material generation should resolve Gemini analysis model to a real image generation agent');
}

for (const required of ['【总卖点】', '【本图卖点】', '【本图风格】', '【产品一致性】']) {
  if (!html.includes(required) || !imageGenerationService.includes(required)) {
    fail(`custom Gemini prompt flow should include ${required}`);
  }
}

if (!html.includes('collectKaiPinMaterialPromptOverrides') ||
    !html.includes("formData.append('materialPromptOverrides'") ||
    !html.includes('kp-db-prompt-editor') ||
    !html.includes('kp-db-image-wrap') ||
    !html.includes('kp-db-check') ||
    !html.includes('kp-db-editor-label') ||
    !kpMaterialController.includes('@RequestParam(value = "materialPromptOverrides", required = false) String materialPromptOverrides') ||
    !kpMaterialController.includes('parseMaterialPromptOverrides(materialPromptOverrides)') ||
    !kpMaterialController.includes('materialPromptOverridesFinal')) {
  fail('kaipin material database prompts should be editable and submitted as per-material overrides');
}

if (!html.includes("bubble.classList.add('msg-wide')") ||
    !html.includes('kp-analysis-meta') ||
    !html.includes('analysis-actions')) {
  fail('kaipin analysis cards should render inside a wide, stable message panel');
}

if (!imageGenerationService.includes('核心卖点清单') ||
    !imageGenerationService.includes('ensureKaiPinSellingPoints') ||
    !imageGenerationService.includes('buildKaiPinSellingPointValue')) {
  fail('kaipin mode should always list explicit selling points in analysis cards and generation prompts');
}

if (!html.includes('kp-step-mark') ||
    !css.includes('grid-template-areas:') ||
    !css.includes('"product"') ||
    !css.includes('"library"') ||
    !css.includes('"selling"') ||
    !css.includes('.kp-selling-field')) {
  fail('kaipin drawer should use an ordered product -> library -> selling layout');
}
if (css.includes('"product library"') || css.includes('"selling selling"')) {
  fail('kaipin drawer steps should be vertically decoupled, not placed on the same x-axis');
}

if (!html.includes('kp-material-maker') ||
    !html.includes('kp-maker-step') ||
    !html.includes('kp-maker-step-index') ||
    !html.includes('kp-maker-step-body') ||
    !html.includes('kp-maker-step-head') ||
    !html.includes('kp-maker-hint') ||
    !html.includes('kp-material-title-field') ||
    !html.includes('kp-save-material-btn') ||
    !html.includes('上传素材图') ||
    !html.includes('命名并生成提示词') ||
    !html.includes('编辑并保存') ||
    !css.includes('.kp-maker-step') ||
    !css.includes('grid-template-columns: 28px minmax(0, 1fr)') ||
    !css.includes('.kp-material-maker .kp-material-actions') ||
    !css.includes('.kp-strategy-section .kp-material-prompt-field') ||
    !css.includes('.kp-material-maker .kp-output-section') ||
    !css.includes('fit-content(380px) minmax(0, 1fr)') ||
    !css.includes('contain: layout') ||
    !html.includes('function refreshKaiPinMaterialLayout') ||
    !html.includes('requestAnimationFrame(force)') ||
    !css.includes('gap: 18px;') ||
    !css.includes('grid-template-columns: 1fr;') ||
    !css.includes('.kp-maker-step:not(:last-child)::after') ||
    !css.includes('.kp-material-title-field') ||
    !css.includes('.kp-maker-step-head') ||
    !css.includes('.kp-save-material-btn')) {
  fail('kaipin material maker should use a clear ordered collection flow with upload, prompt generation, editing, and save actions');
}
const materialFieldBase = css.match(/\.kp-material-upload-field,\s*\.kp-material-prompt-field\s*\{[\s\S]*?\}/)?.[0] || '';
const unscopedMaterialBlocks = Array.from(css.matchAll(/(^|\n)(?!\s*\.kp-strategy-section)(\s*\.kp-material-(?:upload|prompt)-field\s*\{[\s\S]*?\})/g))
  .map(m => m[2]);
if (!/grid-column:\s*auto\s*;/.test(materialFieldBase) ||
    !/grid-row:\s*auto\s*;/.test(materialFieldBase) ||
    !/margin-bottom:\s*16px\s*;/.test(materialFieldBase) ||
    !/width:\s*100%\s*;/.test(materialFieldBase) ||
    unscopedMaterialBlocks.some(block =>
      /grid-column:\s*2\s*;/.test(block) ||
      /grid-row:\s*2\s*\/\s*span\s*2\s*;/.test(block))) {
  fail('kaipin material maker fields must not force desktop grid columns; they should stay vertically auto-placed');
}
const strategyPromptScoped = css.match(/\.kp-strategy-section\s+\.kp-material-prompt-field\s*\{[\s\S]*?\}/)?.[0] || '';
if (!/grid-column:\s*2\s*;/.test(strategyPromptScoped) ||
    !/grid-row:\s*2\s*\/\s*span\s*2\s*;/.test(strategyPromptScoped)) {
  fail('kaipin strategy two-column layout should be scoped to .kp-strategy-section only');
}

if (html.includes('id="videoModelRail"') ||
    html.includes('video-model-card') ||
    css.includes('.video-model-card') ||
    css.includes('.agent-select-control.video-mode-hidden') ||
    !html.includes('id="videoModelLevel"') ||
    !html.includes('id="videoModelHint"') ||
    !css.includes('.video-model-level-control') ||
    !/\.video-model-level-control\s*\{[\s\S]*?width:\s*82px/.test(css) ||
    !css.includes('.video-model-hint.is-visible')) {
  fail('video mode should reuse the global selector and add only a compact 82px model slider');
}

if (process.exitCode) {
  process.exit(process.exitCode);
}
console.log('ui regression checks passed');
