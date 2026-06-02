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

  // 添加规格
  const specBtn = await page.$('button:has-text("添加规格"),[class*="add-spec"]');
  if(specBtn) {
    await specBtn.click(); await sleep(1500);
    const nameInp = await page.$('input[placeholder*="规格类型"],input[placeholder*="规格名"]');
    if(nameInp) { await nameInp.click({force:true}); await sleep(300); await nameInp.type('颜色',{delay:80}); await sleep(400); }
    for(const nm of ['银色花洒','花洒+软管']) {
      await sleep(400);
      const vi = await page.$('input[placeholder*="规格值"],input[placeholder*="添加规格"]');
      if(vi) { await vi.click({force:true}); await sleep(200); await vi.type(nm,{delay:60}); await sleep(300); await vi.press('Enter'); await sleep(700); }
    }
    await page.mouse.click(600,200); await sleep(1500);
  }

  // 截全页
  await page.screenshot({ path: 'inspect_full.png', fullPage: true });

  // 打印全部 input 包括不可见的
  const all = await page.evaluate(() =>
    [...document.querySelectorAll('input:not([type=hidden]):not([type=file]):not([type=checkbox])')].map(e=>({
      ph: e.placeholder,
      type: e.type,
      cls: e.className.substring(0,80),
      val: e.value,
      vis: e.offsetParent !== null
    }))
  );
  // 只打印有 placeholder 的
  const withPh = all.filter(i => i.ph);
  console.log('有 placeholder 的 inputs:');
  console.log(JSON.stringify(withPh, null, 2));
  console.log('\n无 placeholder 但可见的 inputs:');
  console.log(JSON.stringify(all.filter(i=>!i.ph && i.vis), null, 2));

  await browser.close();
})().catch(e => console.error(e.message));
