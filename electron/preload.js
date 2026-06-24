// 仅暴露需要的 IPC 通道；contextIsolation:true 下渲染层只能看到 window.electronAPI.* 这一组白名单。
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    // 让前端 settings modal 的 📁 浏览按钮弹出系统目录选择框
    pickDir: (defaultPath) => ipcRenderer.invoke('pick-dir', defaultPath || ''),
    // 保存文件：直接把 ArrayBuffer 透传给 main（sandbox 模式下 preload 没有 Node Buffer，
    // 必须在 main 进程里转 Buffer；ArrayBuffer 可被 structured-clone 跨 IPC 传输）。
    saveFile: (filename, arrayBuffer) => ipcRenderer.invoke('save-file', { filename, buffer: arrayBuffer }),
    // 复制图片：走 Electron 原生剪贴板（nativeImage），绕开浏览器 Clipboard API 的 MIME 类型校验。
    copyImage: (arrayBuffer) => ipcRenderer.invoke('copy-image', arrayBuffer),
});
