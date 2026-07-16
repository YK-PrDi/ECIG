(function (global) {
    'use strict';

    function clampPanelWidth(value, min, max) {
        const width = Number.isFinite(Number(value)) ? Number(value) : min;
        return Math.min(max, Math.max(min, width));
    }

    function preserveCameraCenter(camera, scale, oldSize, newSize) {
        if (!oldSize.width || !oldSize.height || !scale) return { ...camera };
        const worldX = (oldSize.width / 2 - camera.x) / scale;
        const worldY = (oldSize.height / 2 - camera.y) / scale;
        return {
            x: newSize.width / 2 - worldX * scale,
            y: newSize.height / 2 - worldY * scale
        };
    }

    global.AiStudioWorkbenchLayout = {
        clampPanelWidth,
        preserveCameraCenter
    };
})(window);
