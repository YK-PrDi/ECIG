# 品类提示词分层模板

## 目录结构

```
templates/
├─ roots/        18 个根类目的 subjectLock + negative 模板
└─ branches/     重要二级/三级分支的细化模板（按需补）
```

## 工作机制

`buildEcommercePrompts` 沿当前品类完整路径**从叶子向根冒泡**，找到第一个含 `subjectLock` / `negative` 的注册项即停。

优先级（从高到低）：
1. **叶子专属**（`frontend/data/categories/*.js`） — 已为 4 个高频叶子手写
2. **二级/三级模板**（`templates/branches/*.js`） — 在 roots/ 之上的细化
3. **根模板**（`templates/roots/*.js`） — 18 个根全覆盖
4. **全局兜底**（`index.html` 里的 `EC_SUBJECT_LOCK` / `EC_NEGATIVE`）

## 写新模板

每份模板 = 一个 IIFE 注册到 `window.EC_CATALOG[path]`：

```js
(function () {
    window.EC_CATALOG = window.EC_CATALOG || {};
    window.EC_CATALOG['住宅家具'] = {                  // 路径：用 > 拼接，根类目就 1 段
        subjectLock: '【主体一致性·住宅家具】...',     // 高优先级，整段在 prompt 起首
        negative:    '【禁止-住宅家具】...'             // 末尾负向清单
    };
})();
```

## 编写要点

参考 `categories/guo-gai-jia.js` 的写法（391 字 subjectLock + 462 字 negative）：

1. **方括号标注**：用 `【主体一致性-...】`、`【禁止-...】` 包起来。`PromptCondenser` 系统规则把这种段落列为"必须一字不动保留"，超长 prompt 走 Gemini 压缩时不会被吃掉
2. **Z-depth 顺序**：明确"墙面 → 基座 → ... → 被挂物 / 操作"的前后层级，特别针对有"基座+挂物"或"框体+内容物"结构的品类
3. **零件数量锁死**：列出该品类高频被 AI 错改的部件（喷孔/按键/旋钮/喷头/抽屉/把手...）
4. **物理接触**：手部抓握/产品-墙/桌-地接触必须表面贴合不可穿透
5. **接触阴影**：要求接触点有可见的接触阴影/压痕
6. **多 SKU 同框**：左右对比、四宫格场景下每格各自遵守上述
7. **logo/文字**：禁止乱码、镜像、翻译

## 加载

由 `index.html` 的 `<script src>` 列表统一加载（顺序：catalog.js → roots → branches → categories）：

```html
<script src="data/templates/roots/zhu-zhai-jia-ju.js"></script>
<script src="data/templates/roots/jia-zhuang-zhu-cai.js"></script>
...
```

任何模板缺失时静默跳过；`ecCatalogResolveUp(path, field)` 在 `catalog.js` 里实现冒泡查找。
