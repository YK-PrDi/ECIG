const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'frontend', 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(root, 'frontend', 'index.css'), 'utf8');

function fail(message) {
  console.error(message);
  process.exit(1);
}

const optionsMatch = html.match(/const\s+CUSTOM_STYLE_OPTIONS\s*=\s*\[([\s\S]*?)\];/);
if (!optionsMatch) {
  fail('custom style selector should use CUSTOM_STYLE_OPTIONS data instead of hand-written cards');
}

const optionsSource = optionsMatch[1];
const optionCount = (optionsSource.match(/value:\s*['"]/g) || []).length;
if (optionCount < 21) {
  fail(`custom style selector should expose at least 21 style options, found ${optionCount}`);
}

if (!/category:\s*['"]color['"]/.test(optionsSource) || !/category:\s*['"]scene['"]/.test(optionsSource)) {
  fail('custom style options should include both color and scene categories');
}

for (const requiredValue of ['cyber-black', 'mint-cyan', 'lavender-purple', 'black-gold', 'home-lifestyle', 'outdoor-camping', 'office-desktop']) {
  if (!optionsSource.includes(`value: '${requiredValue}'`) && !optionsSource.includes(`value: "${requiredValue}"`)) {
    fail(`custom style options should include ${requiredValue}`);
  }
}

for (const requiredText of ['配色提示词', '场景提示词', '产品主体保持一致']) {
  if (!optionsSource.includes(requiredText) && !html.includes(requiredText)) {
    fail(`custom style prompts should include "${requiredText}"`);
  }
}

if (!html.includes('function renderCustomStyleCards()') ||
    !html.includes('customStyleCards') ||
    !html.includes('renderCustomStyleCards();')) {
  fail('custom style cards should be rendered from the style option data');
}

const gridBlock = css.match(/\.custom-style-grid\s*\{[\s\S]*?\}/)?.[0] || '';
if (!/grid-template-columns:\s*repeat\(7,\s*minmax\(0,\s*1fr\)\)/.test(gridBlock)) {
  fail('custom style grid should render 7 cards per desktop row');
}

const modalBlock = css.match(/\.custom-style-modal-card\s*\{[\s\S]*?\}/)?.[0] || '';
if (!/width:\s*min\(1280px,\s*96vw\)/.test(modalBlock)) {
  fail('custom style modal should be widened for the 7-column grid');
}

console.log('custom style grid checks passed');
