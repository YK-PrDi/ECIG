(function (global) {
    'use strict';

    function hasEditablePixels(pixelData) {
        for (let index = 3; index < pixelData.length; index += 4) {
            if (pixelData[index] < 250) return true;
        }
        return false;
    }

    async function prepare(imagePromise, maskPromise) {
        const [imageFile, maskBlob] = await Promise.all([imagePromise, maskPromise]);
        if (!imageFile) throw new Error('原图尚未准备完成');
        if (!maskBlob || !maskBlob.size) throw new Error('蒙版为空，请先圈选修改区域');
        return { imageFile, maskBlob };
    }

    global.AiStudioInpaintSession = {
        hasEditablePixels,
        prepare
    };
})(window);
