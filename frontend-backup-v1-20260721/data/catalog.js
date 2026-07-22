// 电商模式 — 品类专属数据注册表
// 结构：EC_CATALOG[path] = {
//   fields:      {fieldKey: [{display,value}]},
//   sellings:    [{key,label,prompt,composition,shapes?}],
//   subjectLock: '可选 — 本品类专属的"主体一致性"段，覆盖全局 EC_SUBJECT_LOCK',
//   negative:    '可选 — 本品类专属的"禁止"段，覆盖全局 EC_NEGATIVE'
// }
//   path         : 完整品类路径（用 '>' 拼接，如 '家装主材>卫浴用品>卫浴五金/挂件>卷纸器/纸巾架'）
//   fields       : 品类专属字段的预设选项；字段 key 与 window.EC_FIELDS 中的 key 一致（安装方式/形态/场景/等）
//   sellings     : 该品类候选卖点；shapes 可选，命中时进一步按"形态结构"当前值过滤
//   subjectLock  : 不同品类的物理结构差异巨大（基座+挂钩+被挂物 vs 单体置物架），全局约束盖不住时品类作者可重写
//   negative     : 同上，列出本品类高频出现的"穿模/物理违和"具体表现
//
// 注意：
// - 各品类数据文件通过 window.EC_CATALOG[path] = {...} 注册；脚本加载顺序由 index.html 控制
// - 未在注册表中的品类会在 UI 里显示为"禁用状态"（没有任何专属预设）
window.EC_CATALOG = window.EC_CATALOG || {};

// 判断给定品类路径是否有注册（用于 UI 禁用态判断）
window.ecCatalogHas = function (path) {
    return !!(path && window.EC_CATALOG[path]);
};

// 读取品类数据；未注册时返回一个"空壳"，调用方无需判空
window.ecCatalogGet = function (path) {
    return window.EC_CATALOG[path] || { fields: {}, sellings: [] };
};

// 沿品类路径从叶子向根冒泡查找首个含指定字段的注册项。
// 用例：叶子没写 subjectLock 时自动继承父级/根的；根没写则返回 null（调用方走全局兜底）。
//   path  : 完整路径字符串，如 '家装主材>卫浴用品>...>卷纸器/纸巾架'
//   field : 'subjectLock' / 'negative' / 'fields' 等
window.ecCatalogResolveUp = function (path, field) {
    if (!path) return null;
    const segs = path.split('>');
    for (let i = segs.length; i >= 1; i--) {
        const cur = segs.slice(0, i).join('>');
        const node = window.EC_CATALOG[cur];
        if (node && node[field] != null && node[field] !== '' &&
            !(Array.isArray(node[field]) && node[field].length === 0) &&
            !(typeof node[field] === 'object' && !Array.isArray(node[field]) && Object.keys(node[field]).length === 0)) {
            return node[field];
        }
    }
    return null;
};
