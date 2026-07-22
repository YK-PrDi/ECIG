(function (global) {
    'use strict';

    function normalizeRect(rect) {
        const x2 = rect.x + rect.w;
        const y2 = rect.y + rect.h;
        return {
            x: Math.min(rect.x, x2),
            y: Math.min(rect.y, y2),
            w: Math.abs(rect.w),
            h: Math.abs(rect.h)
        };
    }

    function intersectsRect(node, rect) {
        const box = normalizeRect(rect);
        return node.x < box.x + box.w && node.x + node.w > box.x &&
            node.y < box.y + box.h && node.y + node.h > box.y;
    }

    function selectionIds(nodes, rect, currentIds, additive) {
        const ids = additive ? new Set(currentIds) : new Set();
        nodes.forEach(node => {
            if (intersectsRect(node, rect)) ids.add(node.id);
        });
        return ids;
    }

    function groupMove(nodes, selectedIds, dx, dy) {
        return nodes.map(node => selectedIds.has(node.id)
            ? { ...node, x: node.x + dx, y: node.y + dy }
            : { ...node });
    }

    function batchTargets(nodes, selectedIds) {
        return nodes.filter(node => selectedIds.has(node.id) && (node.sourcePath || node.savePath));
    }

    global.AiStudioCanvasSelection = {
        normalizeRect,
        intersectsRect,
        selectionIds,
        groupMove,
        batchTargets
    };
})(window);
