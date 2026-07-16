const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = (process.env.AI_STUDIO_BASE_URL || '').replace(/\/$/, '');
const username = process.env.AI_STUDIO_SMOKE_USERNAME || '';
const password = process.env.AI_STUDIO_SMOKE_PASSWORD || '';

if (!baseUrl || !username || !password) {
    console.error('请设置 AI_STUDIO_BASE_URL、AI_STUDIO_SMOKE_USERNAME、AI_STUDIO_SMOKE_PASSWORD');
    process.exit(1);
}

async function run() {
    const executablePath = path.join(
        __dirname,
        'browsers',
        'chromium_headless_shell-1223',
        'chrome-headless-shell-win64',
        'chrome-headless-shell.exe'
    );
    const browser = await chromium.launch({
        headless: true,
        executablePath: fs.existsSync(executablePath) ? executablePath : undefined,
        args: ['--no-proxy-server']
    });

    try {
        const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
        const login = await context.request.post(`${baseUrl}/api/auth/login`, {
            data: { username, password }
        });
        const loginBody = await login.json();
        if (!login.ok() || !loginBody.success) throw new Error('画布拖拽检查登录失败');

        const page = await context.newPage();
        await page.goto(`${baseUrl}/index.html`, { waitUntil: 'networkidle', timeout: 30000 });
        await page.evaluate(() => {
            const source = document.createElement('canvas');
            source.width = 640;
            source.height = 420;
            const context = source.getContext('2d');
            context.fillStyle = '#dbeafe';
            context.fillRect(0, 0, source.width, source.height);
            context.fillStyle = '#0057ff';
            context.fillRect(80, 60, 480, 300);
            board.addImage(source.toDataURL('image/png'), null);
        });
        await page.waitForFunction(() => board.nodes.length === 1);

        const points = await page.evaluate(() => {
            const node = board.nodes[0];
            const screen = board.worldToScreen(node.x + node.w / 2, node.y + node.h / 2);
            const canvas = document.getElementById('boardCanvas').getBoundingClientRect();
            const chat = document.getElementById('chatPanel').getBoundingClientRect();
            return {
                start: { x: canvas.left + screen.x, y: canvas.top + screen.y },
                target: { x: chat.left + 70, y: chat.top + chat.height / 2 }
            };
        });

        await page.mouse.move(points.start.x, points.start.y);
        await page.mouse.down();
        await page.mouse.move(points.target.x, points.target.y, { steps: 18 });
        await page.waitForTimeout(80);

        const duringDrag = await page.evaluate(() => {
            const canvas = document.getElementById('boardCanvas');
            const context = canvas.getContext('2d');
            const stripWidth = Math.min(140, canvas.width);
            const pixels = context.getImageData(canvas.width - stripWidth, 0, stripWidth, canvas.height).data;
            let bluePixels = 0;
            for (let index = 0; index < pixels.length; index += 4) {
                if (pixels[index] < 60 && pixels[index + 1] < 150 && pixels[index + 2] > 190) {
                    bluePixels++;
                }
            }
            return {
                bluePixels,
                dropTargetActive: document.getElementById('chatPanel').classList.contains('canvas-drop-target')
            };
        });

        if (!duringDrag.dropTargetActive) throw new Error('拖入右栏时未显示引用投放状态');
        if (duringDrag.bluePixels > 20) {
            throw new Error(`拖入右栏时画布边缘仍残留图片图层：${duringDrag.bluePixels} 像素`);
        }

        await page.mouse.up();
        await page.waitForFunction(() => droppedFiles.length === 1);
        const afterDrop = await page.evaluate(() => ({
            dropTargetActive: document.getElementById('chatPanel').classList.contains('canvas-drop-target'),
            previewVisible: getComputedStyle(document.getElementById('imagePreviewContainer')).display
        }));
        if (afterDrop.dropTargetActive) throw new Error('松开后右栏投放状态未清理');
        if (afterDrop.previewVisible === 'none') throw new Error('松开后图片未加入右侧引用区');

        console.log('canvas to chat drag check passed');
    } finally {
        await browser.close();
    }
}

run().catch(error => {
    console.error(error.message);
    process.exit(1);
});
