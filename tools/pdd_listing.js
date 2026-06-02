#!/usr/bin/env node
/**
 * pdd_listing.js — 拼多多商品自动发布脚本
 *
 * 使用方式：
 *   node pdd_listing.js                    # 从 stdin 读取 JSON 配置
 *   node pdd_listing.js --login-only       # 仅登录并保存 cookies
 *   node pdd_listing.js --dry-run          # 截图验证每步，不实际提交
 *
 * 配置通过环境变量 PDD_CONFIG 或 stdin 传入（JSON 格式）
 *
 * 进度输出格式（stdout）：
 *   PROGRESS:10:步骤描述
 *   DONE:success
 *   ERROR:错误信息
 */

const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const readline = require('readline');

// ── 工具函数 ──────────────────────────────────────────────────────────────

/** 随机延迟，模拟人类操作节奏 */
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
function rand(min, max) { return Math.floor(Math.random() * (max - min + 1)) + min; }
async function humanDelay(min = 300, max = 800) { await sleep(rand(min, max)); }

/** 随机鼠标移动，打乱操作轨迹 */
async function randomMouseMove(page) {
    const x = rand(300, 900);
    const y = rand(300, 700);
    await page.mouse.move(x, y, { steps: rand(5, 15) });
    await sleep(rand(100, 300));
}

/** 模拟人类点击（先移动再点击） */
async function humanClick(el, opts = {}) {
    await el.hover();
    await sleep(rand(80, 200));
    await el.click({ force: true, ...opts });
}

/** 模拟人类输入（先清空再逐字输入） */
async function humanType(page, el, text) {
    await humanClick(el);
    await sleep(rand(150, 350));
    await el.selectText().catch(() => {});
    await el.type(text, { delay: rand(60, 140) });
}

function progress(pct, msg) {
    console.log(`PROGRESS:${pct}:${msg}`);
}

function done(msg = 'success') {
    console.log(`DONE:${msg}`);
}

function error(msg) {
    console.log(`ERROR:${msg}`);
    process.exit(1);
}

function log(msg) {
    console.log(`LOG:${msg}`);
}

/** 强制设置 React 受控输入框的值（避免追加 bug） */
async function setInputValue(page, selector, value) {
    await page.evaluate(({ sel, val }) => {
        const el = document.querySelector(sel);
        if (!el) return;
        const nativeSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
        nativeSetter.call(el, val);
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
    }, { sel: selector, val: value });
}

/** 暴露隐藏的 file input，返回暴露后的 element handle */
async function exposeFileInput(page, index) {
    await page.evaluate((idx) => {
        const inputs = document.querySelectorAll('input[type="file"]');
        if (inputs[idx]) {
            inputs[idx].style.cssText = `position:fixed;top:${idx * 40 + 100}px;left:0;z-index:999999;opacity:1;width:150px;height:36px;display:block;visibility:visible;`;
            inputs[idx].setAttribute('data-exposed-idx', idx);
        }
    }, index);
    await page.waitForTimeout(300);
}

/** 上传图片到指定的图片区域（按区域索引，只计图片类型 file input）*/
async function uploadImagesToArea(page, areaIndex, imgDir) {
    if (!imgDir || !fs.existsSync(imgDir)) {
        log(`图片目录不存在，跳过：${imgDir}`);
        return 0;
    }
    const files = fs.readdirSync(imgDir)
        .filter(f => /\.(jpg|jpeg|png|webp)$/i.test(f))
        .sort()
        .map(f => path.join(imgDir, f));
    if (files.length === 0) { log(`目录为空，跳过：${imgDir}`); return 0; }

    for (let i = 0; i < files.length; i++) {
        // 只取 accept 包含 image 的 file input
        const imgInputs = await page.$$('input[type="file"][accept*="image"]');
        if (!imgInputs[areaIndex]) { log(`找不到第 ${areaIndex} 个图片上传区`); break; }
        // 暴露该 input
        await page.evaluate((el) => {
            el.style.cssText = 'position:fixed;top:100px;left:0;z-index:999999;opacity:1;width:150px;height:36px;display:block;visibility:visible;';
        }, imgInputs[areaIndex]);
        await page.waitForTimeout(300);
        // 重新获取（DOM 可能重排）
        const refreshed = await page.$$('input[type="file"][accept*="image"]');
        if (refreshed[areaIndex]) {
            await refreshed[areaIndex].setInputFiles(files[i]);
            await page.waitForTimeout(1800);
        }
    }
    return files.length;
}

// ── 主流程 ────────────────────────────────────────────────────────────────

async function main() {
    const args = process.argv.slice(2);
    const loginOnly = args.includes('--login-only');
    const dryRun = args.includes('--dry-run');

    // 读取配置
    let config = {};
    const envConfig = process.env.PDD_CONFIG;
    if (envConfig) {
        try { config = JSON.parse(envConfig); } catch (e) { error('PDD_CONFIG JSON 解析失败: ' + e.message); }
    } else if (!loginOnly) {
        // 从 stdin 读取
        const rl = readline.createInterface({ input: process.stdin });
        let raw = '';
        for await (const line of rl) raw += line;
        if (raw.trim()) {
            try { config = JSON.parse(raw); } catch (e) { error('stdin JSON 解析失败: ' + e.message); }
        }
    }

    const cookiesPath = config.cookiesPath || path.join(process.cwd(), 'pdd_cookies.json');

    // 启动浏览器（始终有界面，拼多多防检测）
    const browser = await chromium.launch({
        headless: false,
        args: ['--no-sandbox', '--disable-blink-features=AutomationControlled'],
    });

    const context = await browser.newContext({
        userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        viewport: { width: 1440, height: 900 },
    });

    // 加载已有 cookies
    if (fs.existsSync(cookiesPath)) {
        try {
            const cookies = JSON.parse(fs.readFileSync(cookiesPath, 'utf8'));
            await context.addCookies(cookies);
            log('已加载登录 cookies');
        } catch (e) {
            log('cookies 加载失败，将重新登录: ' + e.message);
        }
    }

    const page = await context.newPage();

    try {
        // ── STEP 0：检查登录态 ──────────────────────────────────────────
        progress(5, '检查登录状态');
        await page.goto('https://mms.pinduoduo.com/', { waitUntil: 'domcontentloaded', timeout: 30000 });
        await page.waitForTimeout(3000);

        // 可靠的登录态判断：已登录时后台会有导航菜单或跳转到 dashboard 路径
        const currentUrl = page.url();
        const isLoggedIn = await page.evaluate(() => {
            // 有商家后台导航元素，或 URL 包含 dashboard/home/goods 等后台路径
            return !!(
                document.querySelector('[class*="nav-menu"]') ||
                document.querySelector('[class*="sidebar-menu"]') ||
                document.querySelector('[class*="merchant"]') ||
                document.querySelector('.pdd-mms-layout') ||
                (window.location.pathname !== '/' && !window.location.href.includes('login') && !window.location.href.includes('passport'))
            );
        });

        log(`当前URL: ${currentUrl}, 登录态: ${isLoggedIn}`);

        if (!isLoggedIn || loginOnly) {
            log('需要登录，请在弹出的浏览器窗口中完成拼多多商家后台登录...');
            progress(6, '等待用户登录');
            // 确保在登录页
            if (!currentUrl.includes('login') && !currentUrl.includes('passport')) {
                await page.goto('https://mms.pinduoduo.com/login', { waitUntil: 'domcontentloaded', timeout: 30000 });
            }
            // 等待用户登录成功：URL 变成后台页面（含 /home 或 /goods 或 /dashboard，且不含 login/passport）
            await page.waitForFunction(
                () => {
                    const url = window.location.href;
                    return !url.includes('login') &&
                           !url.includes('passport') &&
                           (url.includes('/home') || url.includes('/goods') || url.includes('/dashboard') ||
                            url.includes('/mms.pinduoduo.com/') && document.querySelector('[class*="nav"]'));
                },
                { timeout: 300000, polling: 1000 }
            );
            // 保存 cookies
            const cookies = await context.cookies();
            fs.writeFileSync(cookiesPath, JSON.stringify(cookies, null, 2));
            log('登录成功，cookies 已保存到: ' + cookiesPath);
            if (loginOnly) { await browser.close(); done('login_saved'); return; }
        }

        // ── STEP 1：进入发布新商品页 ────────────────────────────────────
        progress(10, '进入发布新商品页');

        /** 关闭 PDD 后台可能出现的弹窗/广告 */
        async function closePddPopups() {
            try {
                // 常见关闭按钮选择器
                const closeBtns = await page.$$('[class*="modal"] [class*="close"], [class*="dialog"] [class*="close"], [class*="popup"] [class*="close"], button:has-text("关闭"), button:has-text("我知道了"), button:has-text("取消"), [class*="modal-close"], [aria-label*="关闭"]');
                for (const btn of closeBtns) {
                    const visible = await btn.isVisible().catch(() => false);
                    if (visible) {
                        await btn.click({ force: true });
                        await humanDelay(300, 600);
                        log('已关闭弹窗');
                    }
                }
                // 点击遮罩层关闭
                const overlay = await page.$('[class*="mask"]:not(#mms-header__mask), [class*="overlay"], [class*="modal-bg"]');
                if (overlay) {
                    const visible = await overlay.isVisible().catch(() => false);
                    if (visible) { await page.keyboard.press('Escape'); await humanDelay(300, 500); }
                }
            } catch (_) {}
        }

        // 先尝试直接进品类选择页
        await page.goto('https://mms.pinduoduo.com/goods/category', { waitUntil: 'domcontentloaded', timeout: 30000 });
        await page.waitForTimeout(2000);

        const currentPageUrl = page.url();
        const isOnCategoryPage = currentPageUrl.includes('category') || currentPageUrl.includes('goods_add') || currentPageUrl.includes('add_goods');
        if (!isOnCategoryPage) {
            log('跳转失败，当前页面: ' + currentPageUrl + '，尝试从商品列表点击"发布新商品"');
            await page.goto('https://mms.pinduoduo.com/goods/goods_list', { waitUntil: 'domcontentloaded', timeout: 30000 });
            await page.waitForTimeout(2000);
            const addLink = await page.$('a[href*="category"], a:has-text("发布新商品")');
            if (addLink) {
                await addLink.click();
                await page.waitForTimeout(3000);
            } else {
                error('找不到"发布新商品"入口，请检查拼多多后台页面结构');
                return;
            }
        }
        log('发布页: ' + page.url());
        // 关闭可能出现的弹窗
        await closePddPopups();
        if (dryRun) { await page.screenshot({ path: 'step1_add_page.png' }); log('截图已保存: step1_add_page.png'); }

        // ── STEP 2：选择商品类目 ────────────────────────────────────────
        progress(15, '选择商品类目');

        // 等待 header 遮罩消失（最多 10 秒），再操作品类
        await page.waitForFunction(
            () => {
                const mask = document.getElementById('mms-header__mask');
                return !mask || mask.offsetParent === null || getComputedStyle(mask).display === 'none';
            },
            { timeout: 10000 }
        ).catch(() => {
            // 超时后强制隐藏遮罩
            return page.evaluate(() => {
                const mask = document.getElementById('mms-header__mask');
                if (mask) mask.style.display = 'none';
            });
        });
        await page.waitForTimeout(500);

        const category = config.category || '';
        if (category) {
            // 品类页的分类搜索框（不是顶部全局搜索框）
            const searchInput = await page.$('input[placeholder*="搜索分类"], input[placeholder*="关键词搜索"]');
            if (searchInput) {
                const keyword = category.split('>').pop().trim();
                await searchInput.click({ force: true });
                await page.waitForTimeout(300);
                await searchInput.type(keyword, { delay: 80 });
                await page.waitForTimeout(2500);

                // 用 locator 按文字精确匹配叶子分类（has-text 匹配包含文字的最小元素）
                // 优先找精确匹配，次选含关键词的第一个可点击结果
                let clicked = false;
                try {
                    const exact = page.locator(`[class*="SPP_searchItem"], [class*="searchItem"], [class*="search-item"]`).filter({ hasText: keyword }).first();
                    await exact.click({ force: true, timeout: 3000 });
                    clicked = true;
                    log('品类点击成功: ' + keyword);
                } catch (_) {}

                if (!clicked) {
                    // fallback：找所有包含 keyword 文字的叶子节点，点第一个
                    const elHandle = await page.evaluateHandle((kw) => {
                        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                        let node;
                        while ((node = walker.nextNode())) {
                            if (node.nodeValue.trim() === kw) {
                                let el = node.parentElement;
                                while (el && el.tagName === 'SPAN') el = el.parentElement;
                                return el;
                            }
                        }
                        return null;
                    }, keyword);
                    const el = elHandle.asElement();
                    if (el) {
                        await el.click({ force: true });
                        log('品类 fallback 点击: ' + keyword);
                    } else {
                        log('未找到品类: ' + keyword);
                    }
                }
                await page.waitForTimeout(1500);
            }
            // 点击确认/下一步按钮
            const confirmBtn = await page.$('button:has-text("确认发布"), button:has-text("确认"), button:has-text("下一步")');
            if (confirmBtn) {
                await confirmBtn.click({ force: true });
                await page.waitForTimeout(2000);
            }
        }
        progress(20, '类目选择完成');

        if (dryRun) { await page.screenshot({ path: 'step2_category.png' }); log('截图已保存: step2_category.png'); }

        // ── STEP 3：上传主图 ────────────────────────────────────────────
        progress(25, '上传主图');
        if (config.mainImgDir) {
            const count = await uploadImagesToArea(page, 0, config.mainImgDir);
            log(`主图上传完成，共 ${count} 张`);
        }
        progress(35, '主图上传完成');

        if (dryRun) { await page.screenshot({ path: 'step3_main_imgs.png' }); log('截图已保存: step3_main_imgs.png'); }

        // ── STEP 4：填写商品标题 ────────────────────────────────────────
        progress(40, '填写商品标题');
        if (config.title) {
            const titleInput = await page.$('input[placeholder*="商品标题"], textarea[placeholder*="商品标题"]');
            if (titleInput) {
                await titleInput.click();
                await titleInput.fill('');
                await titleInput.type(config.title, { delay: 30 });
                await page.waitForTimeout(500);
            }
        }

        // ── STEP 5：填写商品属性 ────────────────────────────────────────
        progress(45, '填写商品属性');
        // 尝试点击"一键复用"
        const reuseBtn = await page.$('button:has-text("一键复用"), [class*="reuse"]');
        if (reuseBtn) {
            await reuseBtn.click();
            await page.waitForTimeout(2000);
            log('已点击一键复用');
        }
        // 手动填写属性（如果有）
        if (config.attributes) {
            for (const [attrName, attrValue] of Object.entries(config.attributes)) {
                // 找到对应属性的输入框或下拉
                const attrLabel = await page.$(`[class*="attr-label"]:has-text("${attrName}"), label:has-text("${attrName}")`);
                if (attrLabel) {
                    const attrInput = await attrLabel.$('xpath=following-sibling::*//input, following-sibling::input');
                    if (attrInput) {
                        await attrInput.fill(attrValue);
                        await page.waitForTimeout(300);
                    }
                }
            }
        }

        // ── STEP 6：上传详情图 ──────────────────────────────────────────
        progress(50, '上传详情图');
        if (config.detailImgDir) {
            // file inputs: idx0=主图, idx1/2/3=视频, idx4=详情图, idx5=白底图
            const count = await uploadImagesToArea(page, 1, config.detailImgDir);
            log(`详情图上传完成，共 ${count} 张`);
        }
        progress(60, '详情图上传完成');

        // ── STEP 7：上传白底图 ──────────────────────────────────────────
        if (config.whiteImgDir && fs.existsSync(config.whiteImgDir)) {
            progress(62, '上传白底图');
            // 白底图区域可能需要滚动才出现，先滚动到底再找
            await page.evaluate(() => window.scrollBy(0, 600));
            await humanDelay(800, 1200);
            // 先尝试区域索引2（可能主图上传后新 input 出现），否则找标签
            const imgInputsNow = await page.$$('input[type="file"][accept*="image"]');
            log(`白底图上传前图片 input 总数: ${imgInputsNow.length}`);
            if (imgInputsNow.length >= 3) {
                const count = await uploadImagesToArea(page, 2, config.whiteImgDir);
                log(`白底图上传完成，共 ${count} 张`);
            } else {
                log('只有' + imgInputsNow.length + '个图片 input，白底图跳过（需手动上传）');
            }
        }

        // ── STEP 8：添加 SKU 规格 ───────────────────────────────────────
        progress(65, '添加SKU规格');
        if (config.skus && config.skus.length > 0) {
            // 先关闭可能出现的弹窗
            await closePddPopups();
            await humanDelay(500, 1000);

            // 新 PDD UI：规格类型输入框已存在（placeholder="规格类型1"）
            // 直接填写，不需要点"添加规格"按钮
            let specNameInput = await page.$('input[placeholder*="规格类型"]');
            if (!specNameInput) {
                // 旧 UI：点击添加规格按钮
                const addSpecBtn = await page.$('button:has-text("添加规格"), [class*="add-spec"]');
                if (addSpecBtn) {
                    await addSpecBtn.click();
                    await humanDelay(800, 1500);
                    specNameInput = await page.$('input[placeholder*="规格类型"], input[placeholder*="规格名"]');
                }
            }

            if (specNameInput) {
                // 规格类型是下拉选择器，需要点击后从列表里选预设类型
                // PDD 常见规格类型：款式、颜色、型号、尺码 等
                const specTypeName = config.skuSpecType || '款式';
                await specNameInput.click({ force: true });
                await humanDelay(800, 1200);
                // 等下拉列表出现，找对应选项
                try {
                    const opt = await page.waitForSelector(
                        `[class*="dropdown"] li:has-text("${specTypeName}"), [class*="option"]:has-text("${specTypeName}"), [class*="SL_item"]:has-text("${specTypeName}"), [class*="select-item"]:has-text("${specTypeName}"), li:has-text("${specTypeName}")`,
                        { timeout: 5000 }
                    );
                    await opt.click({ force: true });
                    await humanDelay(500, 800);
                    log('规格类型已选择: ' + specTypeName);
                } catch (_) {
                    // 找不到下拉，尝试直接 type
                    await specNameInput.type(specTypeName, { delay: rand(60, 120) });
                    await humanDelay(300, 500);
                    // 再试一次找下拉
                    try {
                        const opt2 = await page.waitForSelector(
                            `li:has-text("${specTypeName}"), [class*="item"]:has-text("${specTypeName}")`,
                            { timeout: 3000 }
                        );
                        await opt2.click({ force: true });
                        log('规格类型已选择(type后): ' + specTypeName);
                    } catch (_2) {
                        log('规格类型下拉未找到，已输入: ' + specTypeName);
                    }
                    await humanDelay(300, 500);
                }
            } else {
                log('未找到规格类型输入框，跳过 SKU 规格');
            }

            // 逐个填写规格值（只取 goods-spec-sku 区域内的 "请输入" input）
            async function getSpecValueInputs() {
                const allInputs = await page.$$('input[placeholder="请输入"]');
                const specInputs = [];
                for (const inp of allInputs) {
                    const inSpec = await page.evaluate(el => {
                        const section = document.getElementById('goods-spec-sku');
                        return section ? section.contains(el) : false;
                    }, inp);
                    if (inSpec) specInputs.push(inp);
                }
                return specInputs;
            }

            const initialSpecInputs = await getSpecValueInputs();
            log(`找到规格值输入框: ${initialSpecInputs.length} 个`);

            for (let i = 0; i < config.skus.length; i++) {
                const sku = config.skus[i];
                await humanDelay(400, 700);

                const specInputs = await getSpecValueInputs();
                const inp = specInputs[i];
                if (inp) {
                    await inp.click({ force: true });
                    await humanDelay(200, 400);
                    await inp.type(sku.name, { delay: rand(60, 120) });
                    await humanDelay(400, 700);
                    // Enter 确认这行，触发新行出现，等待更长时间
                    await inp.press('Enter');
                    await humanDelay(800, 1200);
                    log('规格值已填写: ' + sku.name);
                } else {
                    log('规格值输入框不足，跳过: ' + sku.name);
                }
            }

            // 点击空白区域失焦，等待价格表格渲染
            await page.mouse.click(400, 150);
            await humanDelay(2000, 3000);
            await page.evaluate(() => window.scrollBy(0, 800));
            await humanDelay(800, 1200);

            // 勾选"添加图片"（找不到就跳过）
            const addImgCheckbox = await page.$('input[type="checkbox"][class*="img"], label:has-text("添加图片") input');
            if (addImgCheckbox) {
                try {
                    await addImgCheckbox.check({ force: true, timeout: 3000 });
                    await humanDelay(400, 800);
                } catch (e) {
                    log('添加图片复选框不可用，跳过: ' + e.message.split('\n')[0]);
                }
            }
            if (dryRun) {
                const specState = await page.evaluate(() => {
                    const section = document.getElementById('goods-spec-sku');
                    if (!section) return { chips: [], inputs: [] };
                    const chips = [...section.querySelectorAll('[class*="TAG"], [class*="tag"], [class*="chip"]')]
                        .filter(el => el.offsetParent !== null && el.textContent.trim().length > 0 && el.textContent.trim().length < 40)
                        .map(el => el.textContent.trim().substring(0, 30));
                    const inputs = [...section.querySelectorAll('input')].filter(el => el.offsetParent !== null)
                        .map(el => ({ ph: el.placeholder.substring(0, 30), val: el.value }));
                    return { chips, inputs };
                });
                log('规格区域状态: ' + JSON.stringify(specState));
                await page.screenshot({ path: 'step8_sku_spec.png' });
                log('截图已保存: step8_sku_spec.png');
            }

            // ── STEP 9：上传 SKU 图片 ───────────────────────────────────
            progress(70, '上传SKU图片');
            const allFileInputs = await page.$$('input[type="file"]');
            const skuInputStartIdx = allFileInputs.length - config.skus.length;

            for (let i = 0; i < config.skus.length; i++) {
                const sku = config.skus[i];
                if (sku.imgDir && fs.existsSync(sku.imgDir)) {
                    // imgDir 可以是文件夹路径，也可以是单张图片的文件路径
                    const stat = fs.statSync(sku.imgDir);
                    let skuFile;
                    if (stat.isFile()) {
                        skuFile = sku.imgDir;
                    } else {
                        const skuFiles = fs.readdirSync(sku.imgDir)
                            .filter(f => /\.(jpg|jpeg|png|webp)$/i.test(f))
                            .sort()
                            .map(f => path.join(sku.imgDir, f));
                        skuFile = skuFiles[0];
                    }
                    if (skuFile) {
                        await exposeFileInput(page, skuInputStartIdx + i);
                        const inputs = await page.$$('input[type="file"]');
                        if (inputs[skuInputStartIdx + i]) {
                            await inputs[skuInputStartIdx + i].setInputFiles(skuFile);
                            await page.waitForTimeout(1500);
                        }
                    }
                }
            }

            // ── STEP 10：填写价格和库存 ─────────────────────────────────
            progress(75, '填写价格和库存');
            await randomMouseMove(page);

            // 等待价格表格渲染（SKU 规格添加后需要时间）
            await humanDelay(2000, 3000);
            // 滚动到价格区域
            await page.evaluate(() => {
                const el = document.querySelector('[placeholder*="应大于"], [class*="sku-table"], [class*="price-table"]');
                if (el) el.scrollIntoView({ block: 'center' });
                else window.scrollBy(0, 1000);
            });
            await humanDelay(800, 1200);

            // 先截图 + 打印所有 input（包括不可见的价格相关 input）
            if (dryRun) { await page.screenshot({ path: 'step10_before_price.png' }); log('截图已保存: step10_before_price.png'); }
            const priceAreaInputs = await page.evaluate(() =>
                [...document.querySelectorAll('input:not([type=hidden]):not([type=file]):not([type=checkbox])')].map(e=>({ph:e.placeholder,cls:e.className.substring(0,60),val:e.value,vis:e.offsetParent!==null}))
            );
            const priceRelated = priceAreaInputs.filter(i => i.ph.includes('应大于') || i.ph.includes('单买价') || i.ph.includes('库存') || i.ph.includes('拼单价') || i.ph.includes('团购价'));
            log('价格相关inputs: ' + JSON.stringify(priceRelated));
            log('所有可见inputs数量: ' + priceAreaInputs.filter(i=>i.vis).length);

            // 找价格 input（不限制可见性，只匹配 placeholder）
            const priceInputs = await page.$$('input[placeholder*="拼单价"], input[placeholder*="团购价"], input[placeholder*="应大于"]');
            const singlePriceInputs = await page.$$('input[placeholder*="单买价"], input[placeholder*="原价"], input[placeholder*="单独购买"]');
            const stockInputs = await page.$$('input[placeholder*="库存"], input[placeholder*="请输入库存"]');

            log(`找到价格输入框: 拼单价${priceInputs.length}个, 单买价${singlePriceInputs.length}个, 库存${stockInputs.length}个`);

            for (let i = 0; i < config.skus.length; i++) {
                const sku = config.skus[i];
                const groupPriceYuan = (sku.groupPrice / 100).toFixed(2);
                const singlePriceYuan = (sku.singlePrice / 100).toFixed(2);

                await randomMouseMove(page);

                if (priceInputs[i]) {
                    await humanType(page, priceInputs[i], groupPriceYuan);
                    await humanDelay(200, 500);
                }
                if (singlePriceInputs[i]) {
                    await humanType(page, singlePriceInputs[i], singlePriceYuan);
                    await humanDelay(200, 500);
                }
                if (stockInputs[i]) {
                    await humanType(page, stockInputs[i], String(sku.stock || 999));
                    await humanDelay(200, 400);
                }

                // 填写商品编码
                if (sku.itemCode) {
                    const codeInputs = await page.$$('input[placeholder*="商品编码"], input[placeholder*="货号"]');
                    if (codeInputs[i]) {
                        await humanType(page, codeInputs[i], sku.itemCode);
                        await humanDelay(200, 400);
                    }
                }

                await humanDelay(300, 700);
            }
        }

        progress(80, '价格库存填写完成');

        // ── STEP 11：设置满件折扣 ───────────────────────────────────────
        progress(82, '设置满件折扣');
        if (config.discount) {
            const discountSection = await page.$('[class*="discount"], [class*="full-discount"]');
            if (discountSection) {
                const discountInput = await discountSection.$('input');
                if (discountInput) {
                    await discountInput.fill(config.discount.replace('折', ''));
                    await page.waitForTimeout(300);
                }
            }
        }

        // ── STEP 12：设置承诺发货时间 ───────────────────────────────────
        progress(85, '设置承诺发货时间');
        const deliveryOption = await page.$('label:has-text("48小时"), [class*="delivery"]:has-text("48")');
        if (deliveryOption) {
            await deliveryOption.click();
            await page.waitForTimeout(300);
        }

        if (dryRun) {
            await page.screenshot({ path: 'step_final_before_submit.png' });
            log('dry-run 模式，截图已保存，不实际提交');
            await browser.close();
            done('dry_run_complete');
            return;
        }

        // ── STEP 13：提交上架 ───────────────────────────────────────────
        progress(90, '提交上架');
        // 检查错误数
        const errorCount = await page.$eval('[class*="error-count"], [class*="errors"]', el => {
            const text = el.textContent || '';
            const match = text.match(/错误[（(](\d+)[）)]/);
            return match ? parseInt(match[1]) : -1;
        }).catch(() => -1);

        if (errorCount > 0) {
            error(`页面有 ${errorCount} 个错误，请检查后重试`);
            return;
        }

        const submitBtn = await page.$('button:has-text("提交并上架"), button:has-text("发布商品")');
        if (!submitBtn) {
            error('找不到提交按钮');
            return;
        }
        await submitBtn.click();

        // 等待成功页面
        progress(95, '等待发布结果');
        await page.waitForURL('**/goods_add/success**', { timeout: 30000 }).catch(() => {});
        const successText = await page.$('text=提交成功, text=发布成功').catch(() => null);

        if (successText || page.url().includes('success')) {
            progress(100, '商品发布成功');
            // 保存最新 cookies
            const cookies = await context.cookies();
            fs.writeFileSync(cookiesPath, JSON.stringify(cookies, null, 2));
            done('success');
        } else {
            error('提交后未跳转到成功页面，请手动检查');
        }

    } catch (e) {
        log('发生异常: ' + e.message);
        await page.screenshot({ path: 'error_screenshot.png' }).catch(() => {});
        error(e.message);
    } finally {
        await browser.close();
    }
}

main().catch(e => error(e.message));
