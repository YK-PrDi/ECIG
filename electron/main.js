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

// ── 用户数据目录偏好文件（存放在系统 userData，跨次启动稳定保留） ──
function prefFile() {
    return path.join(app.getPath('userData'), 'pref.json');
}

function loadDataDir() {
    try {
        const raw = fs.readFileSync(prefFile(), 'utf8');
        const { dataDir } = JSON.parse(raw);
        if (dataDir) return dataDir;
    } catch (_) {}
    return null;
}

function saveDataDir(dataDir) {
    try {
        fs.mkdirSync(path.dirname(prefFile()), { recursive: true });
        fs.writeFileSync(prefFile(), JSON.stringify({ dataDir }), 'utf8');
    } catch (_) {}
}

// ── 首次运行：弹窗让用户选择存储目录 ──
async function pickDataDir() {
    const result = await dialog.showOpenDialog(splashWin, {
        title: '请选择下载路径',
        message: '配置文件和生成图片将存储在此文件夹',
        properties: ['openDirectory', 'createDirectory'],
        buttonLabel: '选择此文件夹',
    });
    if (result.canceled || !result.filePaths.length) {
        app.quit();
        return null;
    }
    const dataDir = result.filePaths[0];
    saveDataDir(dataDir);
    return dataDir;
}

// ── 初始化数据目录（首次运行选目录，后续沿用） ──
async function prepareDataDir() {
    let dataDir = loadDataDir();
    if (!dataDir) {
        dataDir = await pickDataDir();
        if (!dataDir) return null;
    }

    // 首次使用该目录时，若 config.json 不存在则创建
    const configDst = path.join(dataDir, 'config.json');
    if (!fs.existsSync(configDst)) {
        try {
            const configSrc = res('config.json');
            if (fs.existsSync(configSrc)) {
                fs.copyFileSync(configSrc, configDst);
            } else {
                fs.writeFileSync(configDst, '{}', 'utf8');
            }
        } catch (_) {}
    }

    // 首次使用该目录时，把内置的 大参考/ 复制过去
    const refSrc = app.isPackaged
        ? res('大参考')
        : path.join(__dirname, '..', '大参考');
    const refDst = path.join(dataDir, '大参考');
    if (fs.existsSync(refSrc) && !fs.existsSync(refDst)) {
        try { copyDirSync(refSrc, refDst); } catch (_) {}
    }

    // 首次使用该目录时，把内置的 prompts/ 复制过去
    const promptsSrc = app.isPackaged
        ? res('prompts')
        : path.join(__dirname, '..', 'prompts');
    const promptsDst = path.join(dataDir, 'prompts');
    if (fs.existsSync(promptsSrc) && !fs.existsSync(promptsDst)) {
        try { copyDirSync(promptsSrc, promptsDst); } catch (_) {}
    }

    return dataDir;
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
async function startJava() {
    const javaExe = res('runtime', 'bin', 'java.exe');
    const jarPath = res('app.jar');
    const dataDir = await prepareDataDir();
    if (!dataDir) return;

    // 步骤 2：启动 AI 服务
    setLoadingStep(2);

    const jvmArgs = [
        `-Dspring.web.resources.static-locations=file:${res('frontend').replace(/\\/g, '/')}/`,
        `-Dapp.paths.config-file=${path.join(dataDir, 'config.json').replace(/\\/g, '/')}`,
        `-Dapp.paths.output-dir=${path.join(dataDir, '生成结果').replace(/\\/g, '/')}`,
        `-Dapp.paths.reference-dir=${path.join(dataDir, '大参考').replace(/\\/g, '/')}`,
        `-Dapp.paths.prompts-dir=${path.join(dataDir, 'prompts').replace(/\\/g, '/')}`,
        '-jar', jarPath
    ];

    javaProcess = spawn(javaExe, jvmArgs, {
        cwd:         dataDir,
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

// ── 推进启动画面步骤（0=检查环境, 1=初始化目录, 2=启动服务, 3=等待就绪） ──
function setLoadingStep(index) {
    if (splashWin && !splashWin.isDestroyed()) {
        splashWin.webContents.executeJavaScript(`updateStep(${index})`).catch(() => {});
    }
}

// ── 启动画面 ──
function createSplash() {
    splashWin = new BrowserWindow({
        width: 420, height: 320,
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

    mainWindow.once('ready-to-show', () => {
        if (splashWin && !splashWin.isDestroyed()) {
            splashWin.close();
            splashWin = null;
        }
        mainWindow.show();
        mainWindow.focus();
    });

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

    // 步骤 0 由 loading.html 自身脚本设置；等页面加载完后再推进
    await new Promise(r => {
        if (splashWin.webContents.isLoading()) {
            splashWin.webContents.once('did-finish-load', r);
        } else {
            r();
        }
    });

    // 步骤 1：初始化数据目录（内部含 prepareDataDir，可能弹窗让用户选目录）
    setLoadingStep(1);
    await startJava();   // 内部 prepareDataDir 完成后推进步骤 2

    try {
        // 步骤 3：等待服务就绪
        setLoadingStep(3);
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
