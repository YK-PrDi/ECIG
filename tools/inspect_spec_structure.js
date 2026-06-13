const { chromium } = require('playwright');
const fs = require('fs');

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

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
  if(btn) { await btn.click({force:true}); await sleep(4000); }

  // 滚动到规格区域
  await page.evaluate(() => {
    const el = document.getElementById('goods-spec-sku');
    if(el) el.scrollIntoView({ block: 'center' });
  });
  await sleep(1000);

  // 截图：选款式前
  await page.screenshot({ path: 'spec_before_select.png' });

  // 选款式
  const specInput = await page.$('input[placeholder*="规格类型"]');
  console.log('规格类型 input 存在:', !!specInput);
  if(specInput) {
    await specInput.click({ force: true }); await sleep(1000);
    try {
      const opt = await page.waitForSelector('li:has-text("款式")', { timeout: 3000 });
      await opt.click({ force: true }); await sleep(1500);
      console.log('款式已选择');
    } catch(e) { console.log('款式选项未找到:', e.message); }
  }

  // 截图：选款式后
  await page.screenshot({ path: 'spec_after_select.png' });

  // 找所有 input 并打印带位置信息
  const inputsWithPos = await page.evaluate(() => {
    const sec = document.getElementById('goods-spec-sku');
    if(!sec) return [];
    return [...sec.querySelectorAll('input')].filter(el => el.offsetParent !== null).map(el => {
      const r = el.getBoundingClientRect();
      return {
        ph: el.placeholder,
        val: el.value,
        x: Math.round(r.x),
        y: Math.round(r.y),
        w: Math.round(r.width)
      };
    });
  });
  console.log('\n规格区域 inputs (含位置):');
  console.log(JSON.stringify(inputsWithPos, null, 2));

  // 找"请输入规格名称" — 搜索所有 placeholder
  const allPh = await page.evaluate(() => {
    const sec = document.getElementById('goods-spec-sku');
    if(!sec) return [];
    return [...sec.querySelectorAll('input, [contenteditable]')].map(el => ({
      tag: el.tagName,
      ph: el.placeholder || el.getAttribute('placeholder') || '',
      ce: el.contentEditable,
      cls: el.className.substring(0, 60)
    }));
  });
  console.log('\n全部 input/contenteditable:');
  console.log(JSON.stringify(allPh, null, 2));

  // 找含"规格名称"文字的标签
  const specNameArea = await page.evaluate(() => {
    const nodes = [...document.querySelectorAll('*')].filter(el =>
      el.children.length === 0 && el.textContent.includes('规格名称') && el.textContent.trim().length < 20
    );
    return nodes.map(n => ({ text: n.textContent.trim(), cls: n.className.substring(0,60), tag: n.tagName }));
  });
  console.log('\n含"规格名称"的元素:');
  console.log(JSON.stringify(specNameArea, null, 2));

  await browser.close();
})().catch(e => console.error(e.message));
