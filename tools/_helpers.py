# -*- coding: utf-8 -*-
"""IO + slug + JS 字面量序列化辅助。"""
from __future__ import annotations
import json
import re
from pathlib import Path
from pypinyin import lazy_pinyin, Style


def slugify(name: str) -> str:
    """中文品类名 → 拼音连字符 slug。
    例：'卷纸器/纸巾架' → 'juan-zhi-qi-zhi-jin-jia'
    例：'卫浴置物架' → 'wei-yu-zhi-wu-jia'
    """
    if not name:
        return 'unknown'
    parts = lazy_pinyin(name, style=Style.NORMAL)
    out = []
    for p in parts:
        # 跳过纯标点：openpyxl 单独把 '/' '·' 等给 lazy_pinyin 时会原样返回
        if not re.fullmatch(r'[a-z0-9]+', p):
            continue
        out.append(p)
    if not out:
        return 'unknown'
    return '-'.join(out)


def js_str(s: str) -> str:
    """把字符串安全地序列化成 JS 单引号字面量。"""
    if s is None:
        s = ''
    # JSON 双引号字面量本身就是合法 JS 字面量，最稳
    return json.dumps(str(s), ensure_ascii=False)


def js_str_arr(arr) -> str:
    return '[' + ', '.join(js_str(x) for x in arr) + ']'


def load_existing_categories_tree(text: str) -> dict:
    """从 categories.js 文本里提取所有叶子路径列表。
    用 Node 子进程更稳，但避免引入新依赖：用 JSON-ish 正则取出叶子 display 顺序。
    返回结构：{path: [叶子1, 叶子2, ...]} 简化版，仅用于检测某叶子是否已存在。
    """
    # 提取所有 `display: '...'` 字面量；保留顺序与缩进足以判断是不是叶子已存在
    return {'displays': re.findall(r"display:\s*'([^']+)'", text)}


def detect_indent(line: str) -> str:
    m = re.match(r'^(\s*)', line)
    return m.group(1) if m else ''


def read_text(p: Path) -> str:
    return p.read_text(encoding='utf-8')


def write_text(p: Path, content: str) -> None:
    p.write_text(content, encoding='utf-8')
