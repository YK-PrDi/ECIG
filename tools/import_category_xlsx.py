# -*- coding: utf-8 -*-
"""xlsx → 电商模式品类数据 自动导入器

用法（项目根目录下）：
    python tools/import_category_xlsx.py 文件1.xlsx 文件2.xlsx ...
    python tools/import_category_xlsx.py 大参考/锅盖架/*.xlsx           # 支持 glob
    python tools/import_category_xlsx.py "人工转IT字段流程7【修订版】.xlsx" \\
                                         "人工转IT字段流程8【修订版】.xlsx"

行为：
- 解析所有传入的 xlsx，按品类（C1 列）分组；多 xlsx 同品类自动合并
- 每个品类生成 frontend/data/categories/<拼音 slug>.js
  · 文件已存在 → 增量合并（按 value/label 去重，不删旧的）
  · 不存在 → 直接生成
- 自动追加新叶子到 frontend/data/categories.js（仅在父品类已存在时）
- 自动追加 <script src> 到 frontend/index.html
- 跑完后请刷新页面或重启 Electron 验证；可以用 git diff 查看改动

xlsx 表头要求：
- 第 1 行（C1..Cn）= 列头；C1 = '品类' 写完整路径如 '家装主材>卫浴用品>...>锅盖架'
- 中间列名遵循 fields.js 的 promptKey（如 '安装方式' '形态结构' '场景' '光影' '材质/工艺'）
- 行级别可有：'卖点' '场景构图' '字体模板' 列；同一行的卖点会成为该品类的一条 selling，
  composition 由 '场景构图' + '拍摄角度' + '产品摆放' + '场景布局' + '产品状态' + '产品质感' + '光影' 串接
"""
from __future__ import annotations
import io
import sys
from pathlib import Path

# Windows cmd 默认 GBK，输出 '•' '✓' 等会 UnicodeEncodeError —— 强制 stdout 走 UTF-8
if hasattr(sys.stdout, 'buffer'):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', line_buffering=True)
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', line_buffering=True)

# 让脚本在被直接执行时也能找到同目录的 _helpers / _writer / _xlsx_parse
sys.path.insert(0, str(Path(__file__).parent))

from _helpers import slugify
from _writer import build_category_file, update_categories_tree, update_index_html
from _xlsx_parse import parse_many


# PyInstaller onefile 模式下 __file__ 指向临时解压目录，sys.executable 才是 exe 真实路径。
# 所以用 cwd 当项目根；用户必须从项目根目录调用此脚本/exe（README 已说明）。
PROJECT_ROOT = Path.cwd()


def main(argv):
    if not argv:
        print(__doc__)
        sys.exit(1)

    # --out-dir 让 caller 把品类输出重定向到外部可写目录（打包态由 ResourceController 注入）。
    # 不传则维持源码态行为：直接写 PROJECT_ROOT/frontend/data/。
    out_root = None
    cleaned = []
    skip = False
    for i, a in enumerate(argv):
        if skip:
            skip = False
            continue
        if a == '--out-dir' and i + 1 < len(argv):
            out_root = Path(argv[i + 1])
            skip = True
            continue
        if a.startswith('--out-dir='):
            out_root = Path(a.split('=', 1)[1])
            continue
        cleaned.append(a)
    argv = cleaned

    if out_root:
        DATA_DIR   = out_root / 'data'
        CAT_TREE   = DATA_DIR / 'categories.js'
        CAT_DIR    = DATA_DIR / 'categories'
        INDEX_HTML = None  # 打包态 jar 内 index.html 只读，不能 patch
        print(f'  写入目录 → {DATA_DIR}（外部用户目录）')
    else:
        DATA_DIR   = PROJECT_ROOT / 'frontend' / 'data'
        CAT_TREE   = DATA_DIR / 'categories.js'
        CAT_DIR    = DATA_DIR / 'categories'
        INDEX_HTML = PROJECT_ROOT / 'frontend' / 'index.html'

    # 收集 xlsx 路径，glob 自动展开
    paths = []
    for a in argv:
        p = Path(a)
        if p.is_file():
            paths.append(p)
            continue
        # glob 兜底（Windows 下 cmd 不展开通配）
        matched = list(Path('.').glob(a))
        if matched:
            paths.extend(matched)
        else:
            print(f'  ⚠ 跳过：{a}（找不到文件）')
    if not paths:
        print('没有可用 xlsx 文件，退出')
        sys.exit(1)

    print(f'解析 {len(paths)} 个 xlsx ...')
    for p in paths:
        print(f'  • {p.name}')
    data = parse_many(paths)

    if not data:
        print('未提取到任何品类数据，请检查 C1 是否有完整品类路径')
        sys.exit(2)

    CAT_DIR.mkdir(parents=True, exist_ok=True)

    print()
    print(f'共解析出 {len(data)} 个品类：')
    for cat_path, payload in data.items():
        slug = slugify(cat_path.split('>')[-1])
        target = CAT_DIR / f'{slug}.js'
        is_new = not target.exists()
        target.write_text(
            build_category_file(cat_path, target, payload['fields'], payload['sellings']),
            encoding='utf-8'
        )
        n_fields = sum(1 for v in payload['fields'].values() if v)
        n_sells = len(payload['sellings'])
        print(f'  {"+" if is_new else "↻"} {cat_path}')
        print(f'      → frontend/data/categories/{slug}.js  字段 {n_fields} | 卖点 {n_sells}')

        # 同步树
        added_tree = update_categories_tree(CAT_TREE, cat_path)
        if added_tree:
            print(f'      ✓ 已追加叶子到 categories.js')
        # 同步 index.html（仅源码态；打包态 jar 内只读，由前端运行时动态 import）
        if INDEX_HTML is not None:
            added_html = update_index_html(INDEX_HTML, slug)
            if added_html:
                print(f'      ✓ 已追加 <script src> 到 index.html')

    print()
    print('完成。建议执行：')
    print('  git diff frontend/data/categories.js frontend/index.html  # 检查自动改动')
    print('  git status frontend/data/categories/                      # 查看新生成/更新的品类文件')


if __name__ == '__main__':
    main(sys.argv[1:])
