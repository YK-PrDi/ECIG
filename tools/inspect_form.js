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

  // 选品类 淋浴花洒套装
  const si = await page.$('input[placeholder*="搜索分类"], input[placeholder*="关键词搜索"]');
  await si.click({ force: true }); await sleep(300);
  await si.type('淋浴花洒套装', { delay: 80 }); await sleep(2500);
  try {
    await page.locator('[class*="SPP_searchItem"]').first().click({ force: true, timeout: 3000 });
  } catch(_) {}
  await sleep(1500);
  const btn = await page.$('button:has-text("确认发布"),button:has-text("确认"),button:has-text("下一步")');
  if(btn) { await btn.click({force:true}); await sleep(3000); }

  // 截初始页面
  await page.screenshot({ path: 'inspect_goods_form.png', fullPage: true });

  // 找所有 file input 及其附近的标签文字
  const fileInputInfo = await page.evaluate(() => {
    return [...document.querySelectorAll('input[type="file"]')].map((el, idx) => {
      // 往上找3层，找包含文字的祖先
      let parent = el.parentElement;
      let labelText = '';
      for (let i = 0; i < 8 && parent; i++) {
        const text = parent.textContent.replace(/\s+/g, ' ').trim().substring(0, 100);
        if (text.length > 3 && text.length < 100) { labelText = text; break; }
        parent = parent.parentElement;
      }
      return { idx, accept: el.accept, labelText };
    });
  });
  console.log('File inputs:');
  console.log(JSON.stringify(fileInputInfo, null, 2));

  // 找所有有 placeholder 的 input
  const inputs = await page.evaluate(() =>
    [...document.querySelectorAll('input:not([type=hidden]):not([type=file]):not([type=checkbox])')].map(e=>({
      ph: e.placeholder, type: e.type, cls: e.className.substring(0,60), vis: e.offsetParent !== null
    })).filter(i => i.ph)
  );
  console.log('\nInputs with placeholder:');
  console.log(JSON.stringify(inputs, null, 2));

  await browser.close();
})().catch(e => console.error(e.message));
