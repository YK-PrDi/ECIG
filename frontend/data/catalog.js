// 电商模式 — 品类专属数据注册表
// 结构：EC_CATALOG[path] = { fields: {fieldKey: [{display,value}]}, sellings: [{key,label,prompt,composition,shapes?}] }
//   path         : 完整品类路径（用 '>' 拼接，如 '家装主材>卫浴用品>卫浴五金/挂件>卷纸器/纸巾架'）
//   fields       : 品类专属字段的预设选项；字段 key 与 window.EC_FIELDS 中的 key 一致（安装方式/形态/场景/等）
//   sellings     : 该品类候选卖点；shapes 可选，命中时进一步按"形态结构"当前值过滤
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
