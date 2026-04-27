const { app, BrowserWindow, shell } = require('electron');
const { spawn } = require('child_process');
const path  = require('path');
const http  = require('http');

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

// ── 启动 Spring Boot ──
function startJava() {
    const javaExe = res('runtime', 'bin', 'java.exe');
    const jarPath = res('app.jar');
    const cwd     = res();          // 工作目录含 frontend/ 子目录

    javaProcess = spawn(javaExe, ['-jar', jarPath], {
        cwd,
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
}

// ── 应用启动 ──
app.whenReady().then(async () => {
    createSplash();
    startJava();

    try {
        await waitForServer();
        createMain();
    } catch (e) {
        const { dialog } = require('electron');
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
