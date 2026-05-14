// 电商模式 — 品类多级树
// 结构：[{ display, children: [...] }, ...]
// 维护提示：增加叶子直接在最深层 children 里追加 { display: '名称' } 即可。
window.EC_CATEGORY_TREE = [
    { display: '家装主材', children: [
        { display: '卫浴用品', children: [
            { display: '卫浴五金/挂件', children: [
                { display: '卷纸器/纸巾架' },
                { display: '卫浴置物架' }
            ]}
        ]},
        { display: '厨房', children: [
            { display: '厨房挂件', children: [
                { display: '锅盖架' }
            ]}
        ]}
    ]}
];
