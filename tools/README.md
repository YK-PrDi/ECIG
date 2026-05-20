# tools/ — 工程辅助脚本

## import_category_xlsx.py — xlsx 自动导入器

把"人工转IT字段流程"系列 xlsx 一键导入到电商模式的品类数据。

### 两种运行方式

#### A. exe（推荐，零依赖）

```bash
# 一次性构建（开发机需要 python + pyinstaller）
pip install pyinstaller openpyxl pypinyin
pyinstaller --onefile --name import-tool ^
            --distpath tools/dist --workpath tools/build --specpath tools ^
            --hidden-import=openpyxl --hidden-import=pypinyin ^
            --noconfirm tools/import_category_xlsx.py

# 之后任何机器都能直接跑（无需 Python 环境）
tools/dist/import-tool.exe "新品类.xlsx"
tools/dist/import-tool.exe "*.xlsx"        # glob
```

后端 `/api/import/xlsx` 自动按下面顺序找 runner：
1. `<项目根>/import-tool.exe`（打包后部署位置）
2. `<项目根>/tools/dist/import-tool.exe`（开发位置，本地构建后产物）
3. `python tools/import_category_xlsx.py`（fallback，需用户机器 Python + 依赖）

#### B. 直接 Python（开发期更快迭代）

```bash
pip install openpyxl pypinyin
python tools/import_category_xlsx.py "新品类.xlsx"
```

### 标准流程

1. xlsx 放项目根
2. 跑 exe 或 python 命令
3. 用 `git diff frontend/data/categories.js frontend/index.html` 检查改动
4. 用 `git status frontend/data/categories/` 查看新建/更新的品类文件
5. 浏览器/Electron 重启验证

### xlsx 表头约定

第 1 行（C1..Cn）= 列头。识别的列名（自动剥离【...】（...） 注释）：

| xlsx 列名 | 落地位置 |
|---|---|
| `品类` | 完整路径（`家装主材>...>叶子`），决定 slug 与文件名 |
| `卖点` / `主图卖点` | 行级 → selling.label |
| `场景构图` | 行级 → 拼到 selling.composition |
| `字体模板` | 全局字段 + 行级也会拼进 selling.prompt（"文字模板：xxx"） |
| `安装方式` `形态结构` `材质/工艺` | 字段池（品类专属） |
| `拍摄角度` `产品摆放` `场景布局` `产品状态` `产品质感` `光影` `场景` `场景细节` | 字段池（品类专属） |
| `滤芯个数` | 全局字段（仅 SKU 启用，1-10） |
| `图片类型` `参考图` `画质` `背景风格` `简短提示词` `字体模板` `滤芯个数` | **全局字段会被忽略**（fields.js 统一管理） |

### 数据合并策略（v2 - 文件层合并）

- **不会覆盖**已有的手工补充内容（含 subjectLock、negative、手工字段、手工卖点）
- 字段选项以 `value` 去重，新出现的追加到末尾
- 卖点以 `label` 去重
- xlsx 不带 `subjectLock/negative` 列，所以这两个字段始终保留现有值

### label 启发式精简

xlsx "卖点" 列实际经常写得很长（带排版说明），脚本会自动精简：
- 优先取双引号内容（`高"高颜值猫爪花洒"作主标题...` → `高颜值猫爪花洒`）
- 否则按 `（）` 形式拆分（`强劲吸附 稳固不晃（在图右上角...）` → `强劲吸附 稳固不晃`）
- 否则取首个 `；;。\n` 分隔符前的部分
- 兜底硬截 28 字 + `…`

被切走的内容自动并入 `selling.prompt`，不会丢失。

### 异常排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `ModuleNotFoundError` | 没装依赖 | `pip install openpyxl pypinyin pyinstaller` |
| 输出 0 个品类 | xlsx C1 没填完整路径 | C1 列写 `家装主材>...>叶子` |
| "已追加叶子到 categories.js" 没出现 | 父级路径不存在 | 手工编辑 `frontend/data/categories.js` 加父级链 |
| 卖点数比预期少 | xlsx 列头没匹配 | 改列头叫"卖点"或"主图卖点"；或在 `_xlsx_parse.py` `HEADER_ALIASES` 加别名 |
| label 还是太长 | xlsx 卖点列写法太特殊 | 手工编辑生成的 `.js` 文件改 label；下次跑脚本不会覆盖 |
