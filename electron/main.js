const { app, BrowserWindow, shell, dialog } = require('electron');
const { spawn } = require('child_process');
const path  = require('path');
const http  = require('http');
const fs    = require('fs');

const PORT    = 5020;
const APP_URL = `http://localhost:${PORT}`;

let mainWindow  = null;
let splashWin   = null;
let javaProcess = null;

// ── 路径解析（打包后用 resourcesPath，开发时用 ../dist） ──
function res(...parts) {
    const base = app.isPackaged
        ? process.resourcesPath
        : path.join(__dirname, '..', 'dist');
    return path.join(base, ...parts);
}

// ── 确保 config.json 在可写的 userData 目录，并传给 JVM ──
function prepareConfigDir() {
    const userData = app.getPath('userData');
    const configDst = path.join(userData, 'config.json');
    const configSrc = res('config.json');

    // 首次运行时，若 userData 里还没有 config.json，复制初始模板
    if (!fs.existsSync(configDst)) {
        try {
            if (fs.existsSync(configSrc)) {
                fs.copyFileSync(configSrc, configDst);
            } else {
                fs.writeFileSync(configDst, '{}', 'utf8');
            }
        } catch (_) {}
    }

    // 首次运行时，把内置的 大参考/ 复制到 userData（仅当目标不存在时）
    const refSrc = app.isPackaged
        ? res('大参考')
        : path.join(__dirname, '..', '大参考');
    const refDst = path.join(userData, '大参考');
    if (fs.existsSync(refSrc) && !fs.existsSync(refDst)) {
        try { copyDirSync(refSrc, refDst); } catch (_) {}
    }

    return userData;
}

// 递归复制目录
function copyDirSync(src, dst) {
    fs.mkdirSync(dst, { recursive: true });
    for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
        const s = path.join(src, entry.name);
        const d = path.join(dst, entry.name);
        if (entry.isDirectory()) copyDirSync(s, d);
        else fs.copyFileSync(s, d);
    }
}

// ── 启动 Spring Boot ──
function startJava() {
    const javaExe  = res('runtime', 'bin', 'java.exe');
    const jarPath  = res('app.jar');
    const userData = prepareConfigDir();

    // Spring Boot 搜索静态资源（frontend/）用 resourcesPath，
    // 但 config.json / 生成结果/ 等可写文件放在 userData
    const jvmArgs = [
        `-Dspring.web.resources.static-locations=file:${res('frontend').replace(/\\/g, '/')}/`,
        `-Dapp.paths.config-file=${path.join(userData, 'config.json').replace(/\\/g, '/')}`,
        `-Dapp.paths.output-dir=${path.join(userData, '生成结果').replace(/\\/g, '/')}`,
        `-Dapp.paths.reference-dir=${path.join(userData, '大参考').replace(/\\/g, '/')}`,
        '-jar', jarPath
    ];

    javaProcess = spawn(javaExe, jvmArgs, {
        cwd:         userData,
        stdio:       'ignore',
        windowsHide: true,
        detached:    false,
    });

    javaProcess.on('error', err => {
        console.error('Java 启动失败:', err.message);
    });
}

// ── 等待服务就绪（轮询） ──
function waitForServer(timeoutMs = 90_000) {
    return new Promise((resolve, reject) => {
        const deadline = Date.now() + timeoutMs;
        const check = () => {
            const req = http.get(APP_URL, res => {
                res.resume();
                resolve();
            });
            req.on('error', () => {
                if (Date.now() > deadline) return reject(new Error('服务启动超时'));
                setTimeout(check, 800);
            });
            req.setTimeout(1500, () => { req.destroy(); });
        };
        check();
    });
}

// ── 启动画面 ──
function createSplash() {
    splashWin = new BrowserWindow({
        width: 420, height: 280,
        frame:       false,
        transparent: false,
        resizable:   false,
        center:      true,
        skipTaskbar: true,
        backgroundColor: '#F8FAFC',
        webPreferences: { nodeIntegration: false },
    });
    splashWin.loadFile(path.join(__dirname, 'loading.html'));
}

// ── 主窗口 ──
function createMain() {
    mainWindow = new BrowserWindow({
        width:     1400,
        height:    900,
        minWidth:  960,
        minHeight: 640,
        title:     'AI Studio',
        show:      false,
        backgroundColor: '#F8FAFC',
        webPreferences: {
            nodeIntegration:  false,
            contextIsolation: true,
        },
    });

    mainWindow.loadURL(APP_URL);

    // 页面加载完成后关闭启动画面，显示主窗口
    mainWindow.once('ready-to-show', () => {
        if (splashWin && !splashWin.isDestroyed()) {
            splashWin.close();
            splashWin = null;
        }
        mainWindow.show();
        mainWindow.focus();
    });

    // 页内打开的新标签/外链 → 用系统浏览器打开
    mainWindow.webContents.setWindowOpenHandler(({ url }) => {
        if (!url.startsWith(APP_URL)) shell.openExternal(url);
        return { action: 'deny' };
    });

    mainWindow.on('closed', () => { mainWindow = null; });

    // ── 下载前询问保存位置 ──
    mainWindow.webContents.session.on('will-download', (event, item) => {
        const filename = item.getFilename();
        const ext = path.extname(filename).slice(1).toLowerCase();
        const filters = ext === 'mp4'
            ? [{ name: '视频文件', extensions: ['mp4'] }, { name: '所有文件', extensions: ['*'] }]
            : [{ name: '图片文件', extensions: ['jpg', 'jpeg', 'png', 'webp'] }, { name: '所有文件', extensions: ['*'] }];

        const savePath = dialog.showSaveDialogSync(mainWindow, {
            title: '保存文件',
            defaultPath: path.join(app.getPath('downloads'), filename),
            filters
        });

        if (savePath) {
            item.setSavePath(savePath);
        } else {
            item.cancel();
        }
    });
}

// ── 应用启动 ──
app.whenReady().then(async () => {
    createSplash();
    startJava();

    try {
        await waitForServer();
        createMain();
    } catch (e) {
        dialog.showErrorBox('AI Studio', `服务启动失败，请重试。\n${e.message}`);
        app.quit();
    }
});

// ── 退出时杀掉 Java 进程 ──
app.on('before-quit', () => {
    if (javaProcess) {
        try { javaProcess.kill(); } catch (_) {}
        javaProcess = null;
    }
});

app.on('window-all-closed', () => app.quit());
