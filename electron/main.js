const { app, BrowserWindow, Menu, shell, dialog, ipcMain, clipboard, nativeImage } = require('electron');
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

function loadPref() {
    try {
        const raw = fs.readFileSync(prefFile(), 'utf8');
        return JSON.parse(raw) || {};
    } catch (_) { return {}; }
}

function savePref(patch) {
    try {
        fs.mkdirSync(path.dirname(prefFile()), { recursive: true });
        const current = loadPref();
        fs.writeFileSync(prefFile(), JSON.stringify({ ...current, ...patch }), 'utf8');
    } catch (_) {}
}

function loadDataDir() {
    return loadPref().dataDir || null;
}

function saveDataDir(dataDir) {
    savePref({ dataDir });
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
        `-Dapp.paths.user-data-dir=${dataDir.replace(/\\/g, '/')}`,
        `-Dapp.resources-path=${process.resourcesPath.replace(/\\/g, '/')}`,
        `-Dapp.packaged=${app.isPackaged ? 'true' : 'false'}`,
        // 日志文件位置：指定到 dataDir/logs（与其他数据一起，用户容易找到）
        `-Dlog.dir=${path.join(dataDir, 'logs').replace(/\\/g, '/')}`,
        '-jar', jarPath
    ];

    javaProcess = spawn(javaExe, jvmArgs, {
        cwd:         dataDir,
        stdio:       'pipe',        // 改为 pipe，捕获输出并写入日志
        windowsHide: true,
        detached:    false,
    });

    // 将 stdout/stderr 输出到日志文件（与 Spring Boot 日志分开，记录 JVM 启动问题）
    const jvmLogPath = path.join(dataDir, 'logs', 'jvm.log');
    try {
        fs.mkdirSync(path.dirname(jvmLogPath), { recursive: true });
        const logStream = fs.createWriteStream(jvmLogPath, { flags: 'a' });
        logStream.write(`\n\n========== ${new Date().toISOString()} ==========\n`);
        if (javaProcess.stdout) javaProcess.stdout.pipe(logStream);
        if (javaProcess.stderr) javaProcess.stderr.pipe(logStream);
    } catch (e) {
        console.error('无法创建 JVM 日志文件:', e.message);
    }

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

// ── 中文菜单（替换 Electron 默认英文菜单 File / Edit / View / Window / Help） ──
function installChineseMenu() {
    const template = [
        {
            label: '应用',
            submenu: [
                { label: '重新加载', role: 'reload' },
                { label: '强制刷新', role: 'forceReload' },
                { type: 'separator' },
                { label: '最小化',   role: 'minimize' },
                { type: 'separator' },
                { label: '退出 AI Studio', role: 'quit' },
            ],
        },
        {
            label: '编辑',
            submenu: [
                { label: '撤销',   role: 'undo' },
                { label: '重做',   role: 'redo' },
                { type: 'separator' },
                { label: '剪切',   role: 'cut' },
                { label: '复制',   role: 'copy' },
                { label: '粘贴',   role: 'paste' },
                { label: '全选',   role: 'selectAll' },
            ],
        },
        {
            label: '视图',
            submenu: [
                { label: '实际大小',     role: 'resetZoom' },
                { label: '放大',         role: 'zoomIn' },
                { label: '缩小',         role: 'zoomOut' },
                { type: 'separator' },
                { label: '切换全屏',     role: 'togglefullscreen' },
                { label: '开发者工具',   role: 'toggleDevTools' },
            ],
        },
        {
            label: '帮助',
            submenu: [
                {
                    label: '关于 AI Studio',
                    click: () => {
                        dialog.showMessageBox(mainWindow, {
                            type:    'info',
                            title:   '关于',
                            message: 'AI Studio',
                            detail:  `版本 ${app.getVersion()}\nAI 产品图片生成系统`,
                            buttons: ['好'],
                            noLink:  true,
                        }).catch(() => {});
                    },
                },
            ],
        },
    ];
    Menu.setApplicationMenu(Menu.buildFromTemplate(template));
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
            preload: path.join(__dirname, 'preload.js'),
        },
    });

    mainWindow.webContents.session.clearCache();
    // 打包模式下追加 ?packaged=1，前端据此禁用 xlsx 导入等"开发期专用"功能
    const urlWithFlag = app.isPackaged
        ? (APP_URL.includes('?') ? APP_URL + '&packaged=1' : APP_URL + '?packaged=1')
        : APP_URL;
    mainWindow.loadURL(urlWithFlag);

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

    // ── 关闭前二次确认（支持"不再提醒"） ──
    let confirmedQuit = false;
    mainWindow.on('close', (event) => {
        if (confirmedQuit) return;                       // 已确认，放行
        if (loadPref().skipExitConfirm) return;          // 用户勾过"不再提醒"
        event.preventDefault();

        dialog.showMessageBox(mainWindow, {
            type:            'question',
            buttons:         ['取消', '退出'],
            defaultId:       0,
            cancelId:        0,
            title:           '退出 AI Studio',
            message:         '确定要退出 AI Studio 吗？',
            detail:          '正在进行的生图任务将被中断。',
            checkboxLabel:   '下次退出时不再询问',
            checkboxChecked: false,
            noLink:          true,
        }).then(({ response, checkboxChecked }) => {
            if (response !== 1) return;                  // 用户取消
            if (checkboxChecked) savePref({ skipExitConfirm: true });
            confirmedQuit = true;
            mainWindow.close();
        }).catch(() => {});
    });

    // ── Ctrl+Shift+Q 重新开启退出确认（给勾过"不再询问"的用户反悔入口） ──
    mainWindow.webContents.on('before-input-event', (_e, input) => {
        if (input.control && input.shift && input.key.toLowerCase() === 'q' && input.type === 'keyDown') {
            savePref({ skipExitConfirm: false });
            dialog.showMessageBox(mainWindow, {
                type: 'info', title: 'AI Studio',
                message: '已重新开启退出确认', buttons: ['好'], noLink: true,
            }).catch(() => {});
        }
    });

    // ── 下载：浏览器自然下载也走用户选定的「下载目录」 ──
    mainWindow.webContents.session.on('will-download', (event, item) => {
        const filename = item.getFilename();
        const saveDir = loadPref().downloadDir || loadDataDir() || app.getPath('downloads');
        item.setSavePath(uniquePath(saveDir, filename));
    });
}

// ── IPC：渲染层调 window.electronAPI.pickDir() 弹原生目录选择框 ──
ipcMain.handle('pick-dir', async (_event, defaultPath) => {
    const owner = mainWindow && !mainWindow.isDestroyed() ? mainWindow : undefined;
    const result = await dialog.showOpenDialog(owner, {
        title: '选择文件保存位置',
        defaultPath: defaultPath || app.getPath('pictures'),
        properties: ['openDirectory', 'createDirectory'],
        buttonLabel: '选择此文件夹',
    });
    if (result.canceled || !result.filePaths.length) return null;
    return result.filePaths[0];
});

// ── 生成不冲突的保存路径（同名文件自动加序号） ──
function uniquePath(dir, filename) {
    fs.mkdirSync(dir, { recursive: true });
    const ext  = path.extname(filename);
    const base = path.basename(filename, ext);
    let target = path.join(dir, filename);
    let i = 1;
    while (fs.existsSync(target)) {
        target = path.join(dir, `${base}(${i++})${ext}`);
    }
    return target;
}

// ── IPC：渲染层调 window.electronAPI.saveFile() 写入用户的「下载目录」 ──
// 首次下载弹原生目录选择框让用户选，存为 downloadDir 偏好，之后沿用（与首次启动选数据目录同款体验）。
// 渲染层传来的是 ArrayBuffer（sandbox 下 preload 无 Node Buffer），在 main 进程转 Buffer 写盘。
ipcMain.handle('save-file', async (_event, { filename, buffer }) => {
    try {
        let saveDir = loadPref().downloadDir;
        if (!saveDir || !fs.existsSync(saveDir)) {
            const owner = mainWindow && !mainWindow.isDestroyed() ? mainWindow : undefined;
            const result = await dialog.showOpenDialog(owner, {
                title: '选择图片下载位置',
                message: '以后下载的图片都会保存到此文件夹',
                defaultPath: loadDataDir() || app.getPath('downloads'),
                properties: ['openDirectory', 'createDirectory'],
                buttonLabel: '选择此文件夹',
            });
            if (result.canceled || !result.filePaths.length) {
                return { ok: false, canceled: true };
            }
            saveDir = result.filePaths[0];
            savePref({ downloadDir: saveDir });
        }
        const savePath = uniquePath(saveDir, filename);
        fs.writeFileSync(savePath, Buffer.from(buffer));
        return { ok: true, filePath: savePath };
    } catch (e) {
        return { ok: false, error: e.message };
    }
});

// ── IPC：渲染层调 window.electronAPI.copyImage() 用原生剪贴板复制图片 ──
// 浏览器 Clipboard API 会校验 blob 的 MIME 必须与声明的 image/png 一致，
// 而 /api/proxy-download 返回 application/octet-stream（且源图常为 jpeg）会被拒绝。
// nativeImage 直接按字节解码，无类型校验，打包后可靠。
ipcMain.handle('copy-image', async (_event, arrayBuffer) => {
    try {
        const img = nativeImage.createFromBuffer(Buffer.from(arrayBuffer));
        if (img.isEmpty()) return { ok: false, error: 'decode-failed' };
        clipboard.writeImage(img);
        return { ok: true };
    } catch (e) {
        return { ok: false, error: e.message };
    }
});

// ── 应用启动 ──
app.whenReady().then(async () => {
    installChineseMenu();
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
