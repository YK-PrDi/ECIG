# -*- coding: utf-8 -*-
"""xlsx 数据 → frontend/data/categories/<slug>.js 的写入器。

文件层合并策略（v2）：
- 用 Node 子进程加载现有 .js 抽出 EC_CATALOG[path]（fields/sellings/subjectLock/negative
  以及任意未来扩展字段如 filterCount 选项等），与 xlsx 解析得到的 __auto 在 Python 端合并，
  再生成静态数据文件写回。
- 字段选项以 value 去重；卖点以 label 去重；subjectLock/negative 优先保留现有手工版。
- 旧手工补充内容（不在 xlsx 里的）会被原样保留。
"""
from __future__ import annotations
import json
import re
import subprocess
from pathlib import Path

from _helpers import js_str


HEADER = '''// 电商模式 — 品类：{cat_human}
// 自动生成（tools/import_category_xlsx.py）；脚本运行时已与现有数据做文件层合并，可手工继续编辑后再次运行
(function () {{
    window.EC_CATALOG = window.EC_CATALOG || {{}};
    window.EC_CATALOG[{cat_key}] = {{
        fields: {{
{fields_block}
        }},
        sellings: [
{sellings_block}
        ]{extras_block}
    }};
}})();
'''


# fields.js 里 promptKey → key 的反查表
PROMPT_KEY_TO_KEY = {
    '安装方式':    'install',
    '图片类型':    'imageType',
    '形态':        'shape',
    '参考图':      'reference',
    '附加拍摄指令': 'shortPrompt',
    '画质':        'quality',
    '字体模板':    'fontTemplate',
    '拍摄角度':    'shotAngle',
    '产品摆放':    'placement',
    '场景布局':    'layout',
    '产品状态':    'productState',
    '产品质感':    'texture',
    '光影':        'lighting',
    '场景':        'scene',
    '场景细节':    'sceneDetail',
    '材质/工艺':   'material',
    '背景风格':    'style',
    '滤芯个数':    'filterCount',
}


def _short_display(s: str, limit: int = 30) -> str:
    """长文本截短为下拉显示用。value 永远是完整原文，display 只用于 UI 可读性。"""
    s = (s or '').strip()
    if len(s) <= limit:
        return s
    return s[:limit].rstrip() + '…'


def _norm_value(v: str) -> str:
    """归一化 value 用于去重比较：合并换行/连续空白；去尾标点（。！；）。
    实际写文件时用原 value，仅去重比较用归一化结果。
    """
    if v is None:
        return ''
    s = str(v)
    # 合并所有连续空白（含换行）为单空格
    s = re.sub(r'\s+', ' ', s).strip()
    # 去掉尾部中英文句号/感叹号/分号
    s = re.sub(r'[。！；\.\!\;]+$', '', s).strip()
    return s


def load_existing_via_node(file_path: Path, cat_key: str) -> dict:
    """用 Node 子进程加载现有 .js，返回 EC_CATALOG[cat_key] 的 dict。
    文件不存在或加载失败返回空 dict。
    """
    if not file_path.exists():
        return {}
    js = (
        "const fs = require('fs');"
        "const ctx = { window: {} };"
        f"const code = fs.readFileSync({json.dumps(str(file_path))}, 'utf8');"
        "try { new Function('window', code)(ctx.window); } "
        "catch (e) { console.error('LOAD_FAIL:', e.message); process.exit(2); }"
        f"const v = (ctx.window.EC_CATALOG || {{}})[{json.dumps(cat_key)}] || {{}};"
        "process.stdout.write(JSON.stringify(v));"
    )
    try:
        out = subprocess.run(
            ['node', '-e', js],
            capture_output=True, text=True, encoding='utf-8', timeout=15,
        )
    except FileNotFoundError:
        # 没装 node，退回到正则方式
        return _load_existing_via_regex(file_path)
    if out.returncode != 0:
        # node 加载失败（语法错误等），尝试正则
        return _load_existing_via_regex(file_path)
    try:
        return json.loads(out.stdout) if out.stdout.strip() else {}
    except json.JSONDecodeError:
        return {}


def _load_existing_via_regex(file_path: Path) -> dict:
    """Node 不可用时的兜底：粗略提取 subjectLock/negative 字符串字面量。
    fields/sellings 复杂结构无法用正则可靠提取，这种情况下会丢手工补充的字段值。
    """
    text = file_path.read_text(encoding='utf-8')
    out: dict = {'fields': {}, 'sellings': []}
    for key in ('subjectLock', 'negative'):
        m = re.search(rf"{key}\s*:\s*'((?:[^'\\]|\\.)*)'", text, re.DOTALL)
        if m:
            out[key] = m.group(1).encode().decode('unicode_escape')
    return out


def merge_data(existing: dict, auto_fields: dict, auto_sellings: list) -> tuple[dict, list, dict]:
    """合并 existing（来自现有 .js）与 auto（来自 xlsx）。
    auto_fields 的 key 是 xlsx 的 promptKey（如 '材质/工艺'），需先转成 .js 里的 fkey（如 'material'）。
    返回 (merged_fields, merged_sellings, extras_dict)。
    """
    existing_fields = existing.get('fields') or {}
    existing_sellings = existing.get('sellings') or []

    # 将 auto_fields 的 promptKey 转成 fkey；未在映射表的 key 直接跳过
    auto_by_fkey: dict = {}
    for prompt_key, vals in auto_fields.items():
        fkey = PROMPT_KEY_TO_KEY.get(prompt_key)
        if not fkey:
            continue
        auto_by_fkey[fkey] = vals

    # 字段：以归一化后的 value 去重；auto 在前，existing 中 auto 没有的追加到末尾
    # 归一化用于去重比较，但保留原 value 写文件（避免改变手工设置的 display 与 value）
    merged_fields: dict = {}
    all_keys = set(auto_by_fkey.keys()) | set(existing_fields.keys())
    for k in all_keys:
        a = auto_by_fkey.get(k) or []  # list of value strings
        e = existing_fields.get(k) or []  # list of {display, value}
        a_objs = [{'display': _short_display(v), 'value': v} for v in a]
        seen = {_norm_value(o['value']) for o in a_objs}
        extras = [o for o in e if _norm_value(o.get('value', '')) not in seen]
        merged_fields[k] = a_objs + extras

    # 卖点：以 label 去重
    auto_obj = []
    seen_auto_labels = set()
    for s in auto_sellings:
        lbl = _short_display(s['label'], 24)
        if lbl in seen_auto_labels:
            continue
        seen_auto_labels.add(lbl)
        auto_obj.append({
            'label': lbl,
            'prompt': s['prompt'],
            'composition': s['composition'],
        })

    # existing 卖点：早期工具版本可能写入了未精简的长 label，这里二次清洗
    # 触发清洗条件（任一即可）：
    #   1) label 长度 > 30 字
    #   2) label 含未配对引号 / 省略号 / 分号 / "副标题" 等装饰说明特征
    cleaned_existing = []
    seen_existing_labels = set()
    DIRTY_HINTS = ('"', '“', '”', '…', '；', '副标题', '主标题', '作主标题', '作副标题')
    for s in (existing_sellings or []):
        lbl = (s.get('label') or '').strip()
        needs_clean = len(lbl) > 30 or any(h in lbl for h in DIRTY_HINTS)
        if needs_clean:
            try:
                from _xlsx_parse import _split_selling
                short, _ = _split_selling(lbl)
                if short and short != lbl:
                    lbl = _short_display(short, 24)
            except Exception:
                pass
        # 与 auto 重复去重；自身重复也去（防止旧文件里同 label 多条）
        if lbl in seen_auto_labels or lbl in seen_existing_labels:
            continue
        seen_existing_labels.add(lbl)
        cleaned_existing.append({**s, 'label': lbl})

    merged_sellings = list(auto_obj) + cleaned_existing

    # 附加：subjectLock / negative 优先用 existing 的（xlsx 不带这两列）
    extras: dict = {}
    if existing.get('subjectLock'):
        extras['subjectLock'] = existing['subjectLock']
    if existing.get('negative'):
        extras['negative'] = existing['negative']
    return merged_fields, merged_sellings, extras


def _format_field_options(opts: list) -> str:
    items = ',\n'.join(
        f"                {{ display: {js_str(o['display'])}, value: {js_str(o['value'])} }}"
        for o in opts
    )
    return items


def build_category_file(cat_path: str, file_path: Path, fields: dict, sellings: list) -> str:
    """生成单个 <slug>.js 文件内容（已与 file_path 现有数据合并）。"""
    existing = load_existing_via_node(file_path, cat_path)
    merged_fields, merged_sellings, extras = merge_data(existing, fields, sellings)

    # 字段按已知 key 顺序输出；未知 key 排在最后保持稳定
    known_order = list(PROMPT_KEY_TO_KEY.values())
    pieces = []
    seen_keys = set()
    for fkey in known_order:
        if fkey not in merged_fields or not merged_fields[fkey]:
            continue
        opts = merged_fields[fkey]
        items = _format_field_options(opts)
        pieces.append(f"            {fkey}: [\n{items}\n            ]")
        seen_keys.add(fkey)
    for fkey in sorted(merged_fields):
        if fkey in seen_keys or not merged_fields[fkey]:
            continue
        opts = merged_fields[fkey]
        items = _format_field_options(opts)
        pieces.append(f"            {fkey}: [\n{items}\n            ]")
    fields_block = ',\n'.join(pieces)

    selling_pieces = []
    for s in merged_sellings:
        selling_pieces.append(
            "            { "
            f"label: {js_str(s['label'])},\n"
            f"              prompt: {js_str(s.get('prompt',''))},\n"
            f"              composition: {js_str(s.get('composition',''))} "
            "}"
        )
    sellings_block = ',\n'.join(selling_pieces)

    extras_lines = []
    for k in ('subjectLock', 'negative'):
        if extras.get(k):
            extras_lines.append(f",\n        {k}: {js_str(extras[k])}")
    extras_block = ''.join(extras_lines)

    return HEADER.format(
        cat_human=cat_path.replace('>', ' > '),
        cat_key=js_str(cat_path),
        fields_block=fields_block,
        sellings_block=sellings_block,
        extras_block=extras_block,
    )


def update_categories_tree(tree_js_path: Path, cat_path: str) -> bool:
    """把叶子追加进 EC_CATEGORY_TREE。返回是否新增。"""
    text = tree_js_path.read_text(encoding='utf-8')
    parts = cat_path.split('>')
    leaf = parts[-1]
    if f"display: '{leaf}'" in text or f'display: "{leaf}"' in text:
        return False
    parent_path = parts[:-1]
    parent_name = parent_path[-1] if parent_path else None
    if not parent_name:
        return False
    pattern = re.compile(
        r"(\{\s*display:\s*'" + re.escape(parent_name) + r"'\s*,\s*children:\s*\[)([\s\S]*?)(\n\s*\]\s*\})"
    )
    m = pattern.search(text)
    if not m:
        return False
    head, body, tail = m.group(1), m.group(2), m.group(3)
    body = body.rstrip()
    sep = ',\n                ' if body else '\n                '
    new_body = body + sep + "{ display: '" + leaf + "' }"
    new_text = text[:m.start()] + head + new_body + tail + text[m.end():]
    tree_js_path.write_text(new_text, encoding='utf-8')
    return True


def update_index_html(index_path: Path, slug: str) -> bool:
    """在 index.html 里插入 <script src="data/categories/<slug>.js">（如未存在）。"""
    text = index_path.read_text(encoding='utf-8')
    needle = f'data/categories/{slug}.js'
    if needle in text:
        return False
    anchor = re.search(r'(<!-- 品类专属数据[^\n]*\n)([\s\S]*?)(\n\s*<script>)', text)
    if not anchor:
        return False
    block_start, block_body, after = anchor.group(1), anchor.group(2), anchor.group(3)
    body_lines = block_body.rstrip().split('\n')
    indent = re.match(r'^(\s*)', body_lines[-1]).group(1) if body_lines else '    '
    new_line = f'{indent}<script src="data/categories/{slug}.js"></script>'
    new_block = block_start + block_body.rstrip() + '\n' + new_line + after
    new_text = text[:anchor.start()] + new_block + text[anchor.end():]
    index_path.write_text(new_text, encoding='utf-8')
    return True
