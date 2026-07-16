(function (global) {
    'use strict';

    function buildPlans(items, selectedIds, overrides) {
        const selected = new Set((selectedIds || []).map(String));
        const overrideMap = overrides || {};
        return (items || [])
            .filter(item => selected.has(String(item.id)))
            .map(item => ({
                id: String(item.id),
                title: item.title || '开品素材',
                prompt: String(overrideMap[item.id] || item.prompt || '').trim(),
                imagePath: item.imagePath || ''
            }));
    }

    global.AiStudioKaiPinSelection = { buildPlans };
})(window);
