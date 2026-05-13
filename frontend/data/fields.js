// 电商模式 — 全局字段定义（所有品类通用）
// 结构：[{ key, label, promptKey, global: true, options: [{display,value}] }]
// - global: true  表示与品类无关，始终可选
// - 非 global 的字段（安装方式/形态/拍摄角度/产品摆放/场景布局/产品状态/产品质感/光影/场景/场景细节/材质），
//   其 options 来自对应品类文件 frontend/data/categories/*.js 的 fields[key]；
//   未选中品类时整体禁用，hint "请先选品类"
window.EC_FIELDS = [
    { key: 'imageType', label: '图片类型', promptKey: '图片类型', global: true,
      options: [
        { display: '主图',    value: '主图' },
        { display: 'SKU',     value: 'SKU' },
        { display: '详情页',  value: '详情页' }
      ]
    },
    { key: 'reference', label: '参考图', promptKey: '参考图', global: true,
      options: [
        { display: '单品',                 value: '单品' },
        { display: '双品（两个同样的品）', value: '双品（两个同样的品）' },
        { display: 'AB品（两款规格不同）', value: 'AB品（两款规格不同的产品）' }
      ]
    },
    { key: 'quality', label: '画质', promptKey: '画质', global: true,
      options: [
        { display: '1K', value: '1K' },
        { display: '2K', value: '2K' },
        { display: '4K', value: '4K' }
      ]
    },
    { key: 'style', label: '背景风格', promptKey: '背景风格', global: true,
      options: [
        { display: '轻奢风', value: '轻奢风（深灰大理石哑光纹理墙面，镜面级反光出产品倒影，光影细腻，凸显产品高级质感）' },
        { display: '轻奢风·深灰哑光高反光', value: '轻奢风（深灰哑光高反光墙面，墙面拥有镜面级反光出产品倒影，光影细腻，凸显产品高级质感）' },
        { display: '极简主义', value: '极简主义（无主灯设计、隐藏式收纳、大面积留白）' },
        { display: '侘寂风', value: '侘寂风（微水泥质感、原木色调、哑光表面、自然粗犷）' },
        { display: '现代前卫', value: '现代前卫（科技感、黑白灰主色调、几何线条）' },
        { display: '原木北欧', value: '原木北欧（温暖自然、木质元素、清新明亮）' },
        { display: '新中式', value: '新中式（对称美学、胡桃木色、中式格栅、水墨纹理）' }
      ]
    },
    { key: 'shortPrompt', label: '简短提示词', promptKey: '附加拍摄指令', global: true,
      options: [
        { display: '款式替换+左右分栏特写', value: '将第一张的主图中产品款式进行替换，并在图中最左/右侧留白空间增加 3 行 1 列分栏式小图布局，分别特写展示吸盘结构、承重测试、底部通风效果' },
        { display: '生成 6 张 9:16 详情页', value: '请你结合以上所有主图和sku图生成6张9:16的详情页图片' }
      ]
    },

    // ── 以下为"品类专属"字段，options 留空；实际选项在用户选中品类后从 EC_CATALOG 注入 ──
    { key: 'install',      label: '安装方式',   promptKey: '安装方式',   options: [] },
    { key: 'shape',        label: '形态结构',   promptKey: '形态',       options: [] },
    { key: 'shotAngle',    label: '拍摄角度',   promptKey: '拍摄角度',   options: [] },
    { key: 'placement',    label: '产品摆放',   promptKey: '产品摆放',   options: [] },
    { key: 'layout',       label: '场景布局',   promptKey: '场景布局',   options: [] },
    { key: 'productState', label: '产品状态',   promptKey: '产品状态',   options: [] },
    { key: 'texture',      label: '产品质感',   promptKey: '产品质感',   options: [] },
    { key: 'lighting',     label: '光影',       promptKey: '光影',       options: [] },
    { key: 'scene',        label: '场景',       promptKey: '场景',       options: [] },
    { key: 'sceneDetail',  label: '场景细节',   promptKey: '场景细节',   options: [] },
    { key: 'material',     label: '材质/工艺',  promptKey: '材质/工艺',  options: [] }
];
