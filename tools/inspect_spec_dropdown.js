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
    if(el) el.scrollIntoView({ block: 'center' });
  });
  await sleep(1000);

  // 找规格类型 input 并点击
  const specInput = await page.$('input[placeholder*="规格类型"]');
  if(specInput) {
    await specInput.click({ force: true });
    await sleep(1000);
    await page.screenshot({ path: 'spec_dropdown_open.png' });

    // 打印下拉里的 li 元素
    const opts = await page.evaluate(() =>
      [...document.querySelectorAll('li')].filter(el => el.offsetParent !== null && el.textContent.trim().length < 20)
        .map(el => ({ text: el.textContent.trim(), cls: el.className.substring(0,60) }))
    );
    console.log('下拉 li 元素:', JSON.stringify(opts.slice(0,20), null, 2));

    // 点款式
    try {
      const opt = await page.waitForSelector('li:has-text("款式")', { timeout: 3000 });
      await opt.click({ force: true });
      await sleep(1000);
      console.log('点击了款式');
    } catch(e) { console.log('款式 li 未找到:', e.message); }
  } else {
    console.log('规格类型 input 未找到');
    await page.screenshot({ path: 'spec_no_input.png' });
  }

  await sleep(1000);
  await page.screenshot({ path: 'spec_after_select.png' });

  // 找规格值输入框
  const specValueInputs = await page.evaluate(() =>
    [...document.querySelectorAll('input')].filter(el => el.offsetParent !== null)
      .map(el => ({ ph: el.placeholder, val: el.value, cls: el.className.substring(0,60) }))
      .filter(i => i.ph === '请输入' || i.ph.includes('规格'))
  );
  console.log('规格值区域 inputs:', JSON.stringify(specValueInputs, null, 2));

  // 添加一个规格值
  const vi = await page.$('input[placeholder="请输入"]');
  if(vi) {
    await vi.click({ force: true }); await sleep(200);
    await vi.type('银色花洒', { delay: 80 }); await sleep(300);
    await vi.press('Enter'); await sleep(1000);
    await page.screenshot({ path: 'spec_after_value.png' });

    // 看价格表格
    const priceInputs = await page.evaluate(() =>
      [...document.querySelectorAll('input[placeholder*="应大于"], input[placeholder*="单买价"], input[placeholder*="库存"]')].map(el => ({
        ph: el.placeholder, val: el.value, vis: el.offsetParent !== null
      }))
    );
    console.log('价格 inputs:', JSON.stringify(priceInputs, null, 2));

    // 看 chips（规格值标签）
    const chips = await page.evaluate(() =>
      [...document.querySelectorAll('[class*="tag"], [class*="chip"], [class*="spec-value"], [class*="SKU"]')]
        .filter(el => el.offsetParent !== null && el.textContent.trim().length < 30)
        .map(el => ({ text: el.textContent.trim(), cls: el.className.substring(0,60) }))
    );
    console.log('Chips/Tags:', JSON.stringify(chips.slice(0,20), null, 2));
  }

  await browser.close();
})().catch(e => console.error(e.message));
