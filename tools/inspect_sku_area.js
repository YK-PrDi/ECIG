const { chromium } = require('playwright');
const fs = require('fs');

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
function rand(min, max) { return Math.floor(Math.random() * (max - min + 1)) + min; }

(async () => {
  const browser = await chromium.launch({ headless: false });
  const context = await browser.newContext({
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    viewport: { width: 1440, height: 900 }
  });
  const cookies = JSON.parse(fs.readFileSync('pdd_cookies.json', 'utf8'));
  await context.addCookies(cookies);
  const page = await context.newPage();

  await page.goto('https://mms.pinduoduo.com/goods/category', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await sleep(4000);
  await page.evaluate(() => { const m = document.getElementById('mms-header__mask'); if(m) m.style.display='none'; });
  await sleep(500);

  // 选品类
  const si = await page.$('input[placeholder*="搜索分类"], input[placeholder*="关键词搜索"]');
  await si.click({ force: true }); await sleep(300);
  await si.type('淋浴花洒套装', { delay: 80 }); await sleep(2500);
  try { await page.locator('[class*="SPP_searchItem"]').first().click({ force: true, timeout: 3000 }); } catch(_) {}
  await sleep(1500);
  const btn = await page.$('button:has-text("确认发布"),button:has-text("确认"),button:has-text("下一步")');
  if(btn) { await btn.click({force:true}); await sleep(3000); }

  // 滚动到规格区域
  await page.evaluate(() => {
    const el = document.getElementById('goods-spec-sku');
    if(el) el.scrollIntoView();
  });
  await sleep(1000);

  await page.screenshot({ path: 'sku_before_add.png' });

  // 找"添加规格类型"按钮
  const addBtns = await page.evaluate(() =>
    [...document.querySelectorAll('button, [class*="add"], [class*="btn"]')]
      .filter(el => el.textContent.includes('添加规格') || el.textContent.includes('添加类型') || el.textContent.includes('添加规格类型'))
      .map(el => ({ text: el.textContent.trim().substring(0,50), cls: el.className.substring(0,80), tag: el.tagName }))
  );
  console.log('添加规格按钮:', JSON.stringify(addBtns, null, 2));

  // 找规格区域内所有按钮
  const specBtns = await page.evaluate(() => {
    const section = document.getElementById('goods-spec-sku');
    if(!section) return [];
    return [...section.querySelectorAll('button, [class*="btn"]')]
      .map(el => ({ text: el.textContent.trim().substring(0,60), cls: el.className.substring(0,60) }));
  });
  console.log('规格区域内按钮:', JSON.stringify(specBtns, null, 2));

  // 点击第一个添加规格按钮
  const addBtn = await page.$('#goods-spec-sku button, #goods-spec-sku [class*="add-spec"]');
  if(addBtn) {
    await addBtn.click({ force: true });
    await sleep(2000);
    await page.screenshot({ path: 'sku_after_add.png' });
    console.log('点击了添加规格按钮，截图: sku_after_add.png');
  }

  // 找下拉 + input
  const afterAdd = await page.evaluate(() => {
    const section = document.getElementById('goods-spec-sku');
    if(!section) return 'section not found';
    return [...section.querySelectorAll('input, select, [class*="select"], [class*="dropdown"]')]
      .map(el => ({ tag: el.tagName, ph: el.placeholder||'', cls: el.className.substring(0,80), val: el.value }));
  });
  console.log('添加规格后的 inputs:', JSON.stringify(afterAdd, null, 2));

  await browser.close();
})().catch(e => console.error(e.message));
