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
  await sleep(1500);

  // 找规格类型 input
  const specInput = await page.$('input[placeholder*="规格类型"]');
  console.log('规格类型 input:', !!specInput);

  if(specInput) {
    await specInput.click({ force: true });
    await sleep(1000);
    try {
      const opt = await page.waitForSelector('li:has-text("款式")', { timeout: 3000 });
      await opt.click({ force: true });
      await sleep(1000);
    } catch(e) { console.log('款式选项未找到'); }
  }

  // 找 tag 输入框（IPT_tag 容器）
  const tagInput = await page.$('[class*="IPT_tag"] input, [class*="ST_headInput"][class*="IPT_tag"] input');
  console.log('Tag input (IPT_tag):', !!tagInput);

  if(tagInput) {
    // 截图看当前状态
    await page.screenshot({ path: 'tag_state.png' });

    // 尝试不同按键
    await tagInput.click({ force: true }); await sleep(300);
    await tagInput.type('银色花洒', { delay: 80 }); await sleep(500);

    // 打印 tag 容器的 HTML
    const tagHtml = await page.evaluate(() => {
      const el = document.querySelector('[class*="IPT_tag"]');
      return el ? el.outerHTML.substring(0, 500) : 'not found';
    });
    console.log('Tag 容器 HTML:', tagHtml);

    // 截图后按 Enter
    await page.screenshot({ path: 'tag_before_key.png' });
    await tagInput.press('Enter'); await sleep(800);
    const p1 = (await page.$$('input[placeholder*="应大于"]')).length;
    console.log('Enter后价格行数:', p1);

    // 再试 Space
    await tagInput.type('花洒+软管', { delay: 80 }); await sleep(300);
    await tagInput.press(' '); await sleep(500);
    const p2 = (await page.$$('input[placeholder*="应大于"]')).length;
    console.log('Space后价格行数:', p2);

    // 看 chip 内容
    const chips = await page.evaluate(() =>
      [...document.querySelectorAll('[class*="TAG_outerWrapper"], [class*="tag-item"], [class*="IPT_tag"] [class*="item"]')]
        .filter(el => el.offsetParent !== null)
        .map(el => el.textContent.trim().substring(0, 30))
    );
    console.log('Chips:', JSON.stringify(chips));

    await page.screenshot({ path: 'tag_final.png' });
  } else {
    // 看页面上有哪些 input
    const inp = await page.evaluate(() =>
      [...document.querySelectorAll('input')].filter(el => el.offsetParent !== null)
        .map(el => ({ ph: el.placeholder.substring(0,40), cls: el.className.substring(0,60) }))
    );
    console.log('可见 inputs:', JSON.stringify(inp, null, 2));
    await page.screenshot({ path: 'tag_not_found.png' });
  }

  await browser.close();
})().catch(e => console.error(e.message));
