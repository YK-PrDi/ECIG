const { chromium } = require('playwright');
const fs = require('fs');

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
  await page.waitForTimeout(4000);
  await page.evaluate(() => {
    const m = document.getElementById('mms-header__mask');
    if (m) m.style.display = 'none';
  });
  await page.waitForTimeout(500);

  const searchInput = await page.$('input[placeholder*="搜索分类"], input[placeholder*="关键词搜索"]');
  if (!searchInput) { console.log('no input'); await browser.close(); return; }

  await searchInput.click({ force: true });
  await page.waitForTimeout(300);
  await searchInput.type('花洒', { delay: 100 });
  await page.waitForTimeout(2500);
  await page.screenshot({ path: 'cat_after_search.png' });

  // 找含"花洒"的可点击元素及其父层结构
  const items = await page.evaluate(() => {
    return [...document.querySelectorAll('*')]
      .filter(el => {
        const t = el.textContent.trim();
        return el.children.length <= 3 && t.includes('花洒') && t.length < 80;
      })
      .slice(0, 20)
      .map(el => ({
        tag: el.tagName,
        text: el.textContent.trim().substring(0, 60),
        cls: el.className.substring(0, 100),
        id: el.id
      }));
  });
  console.log(JSON.stringify(items, null, 2));
  await browser.close();
})().catch(e => console.error(e.message));
