# -*- coding: utf-8 -*-
"""roots 模板精简器：删通用模板话术，保品类专属物理知识。

为什么要这个脚本：18 个 roots 文件结构高度规整（subjectLock + negative 各 ~600 字），
其中 30%-40% 是各根类目重复出现的"通用模板话术"（多产品同框、阴影方向、多场景拼图、
接触阴影、logo 错位 等）—— 这些已经在全局 EC_SUBJECT_LOCK + EC_NEGATIVE 里覆盖，
roots 重复一遍只是稀释模型注意力。

脚本逻辑：
1. 用正则提取每个 roots 文件的 subjectLock / negative 字符串值
2. 按精简规则集合替换/删除
3. 确保 ASCII 引号、写回原位置
4. 生成报告：每个文件压缩了多少字
5. 调用方负责跑 `node --check` 验证
"""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROOTS_DIR = ROOT / 'frontend' / 'data' / 'templates' / 'roots'

# ── 精简规则：subjectLock 删除模式（按出现频次从高到低）────────────────────────
# 这些都是"通用模板话术"，跟具体根类目无关；保留它们只会和全局 EC_SUBJECT_LOCK 重复
SUBJECT_LOCK_DELETIONS = [
    # 每个文件开头都有的"画面主体严格以参考图为准" → 改为更短的"严格以参考图保持"
    (r'画面主体严格以参考图(?:中的[^：]*?)?为准[：:]', '严格以参考图保持：'),
    # 中段"如有多张参考图视作同一款产品的不同视角/细节，综合理解后再按要求重绘场景。"
    (r'(?:如有)?多张参考图视作同[^。]*?(?:综合理解后)?(?:再按要求)?重绘[^。]*?。', ''),
    # "所有物体之间遵守真实物理规则：" 通用句
    (r'所有物体之间遵守真实物理规则[：:]?', ''),
    # "前后遮挡关系必须清晰一致..."
    (r'前后遮挡关系必须清晰一致(?:（[^）]*?）)?[；。、，]?', ''),
    # 末尾"一律保持不变。" → "不变。"
    (r'\s*一律保持不变。', '不变。'),
    # "不可相互穿透；" / "不可穿透；"
    (r'(?:不可)?相互穿透[；;]?', ''),
    # 整段"产品与墙面、台面、地面、玻璃门的接触均为表面贴合" → 太具体的家装专属，jia-zhuang 已有，删全局通用部分
    # （jia-zhuang 文件单独保留这段）
    # 句首多余的"，"/"；"留空白
    (r'[，；]\s*[，；]', '；'),
    (r'^[，；。\s]+', ''),
]

# ── 精简规则：negative 删除模式 ─────────────────────────────────────────────
# 删除条款：跟 EC_NEGATIVE 全局已覆盖的通用项，保留各根类目特有的物理穿模约束
NEGATIVE_DELETIONS = [
    # 开头"严禁出现以下任何错误：" / "严禁出现：" / "严禁：" → 全部删（用列表 1) 直接开始）
    (r'严禁出现(?:以下任何错误)?[：:]', ''),
    (r'严禁[：:]', ''),
    # 通用 logo / 铭牌 / CCC 错位条款（每个文件都有，全局 EC_NEGATIVE 已有"watermark/text/产品上出现假 logo"）
    (r';?\s*\d+\)\s*logo[/／]?(?:铭牌|品牌|CCC|认证标志)*(?:错位|错写)?[/／]?(?:镜像|乱码|翻译)+[；。]?', ''),
    # 通用阴影条款（全局 EC_NEGATIVE 已有"阴影方向与光源不一致"）
    (r';?\s*\d+\)\s*阴影方向(?:与光源)?不一致(?:或缺失[^；]*?接触阴影)?[；。]?', ''),
    # 通用"多产品同框时各 SKU 颜色/款式融合"（全局已有"把多场景拼成一张图"约束）
    (r';?\s*\d+\)\s*多产品同框[^；]*?(?:融合|互换|改造为另一种形态)[^；]*?[；。]?', ''),
    # 通用"把多场景拼成一张图"（全局已有）
    (r';?\s*\d+\)\s*把多场景拼[^；]*?[；。]?', ''),
    # 通用"手部/手指穿透产品"条款（全局 EC_NEGATIVE 已有"手指穿过产品"）
    (r';?\s*\d+\)\s*(?:手部|手指)[^；]*?穿(?:透|过)[^；]*?[；。]?', ''),
    # 末尾通用"所有接触点必须有清晰可见的接触边界..." 全局已覆盖
    (r'所有接触点(?:必须有|贴合)[^。]*?。', ''),
    (r'所有接触面(?:必须|贴合)[^。]*?。', ''),
    # 末尾通用"X 真实合理"模板话术
    (r'(?:零件堆叠|工作状态|警示标识完整可读|布艺.*?压痕.*?自然真实|橡塑特性真实呈现|手工痕迹是品类灵魂[^。]*)。\s*$', ''),
    # 末尾"所有人物-工具-墙面-地面的接触关系必须真实合理。"（zhuang-xiu）
    (r'所有人物-工具-墙面-地面的接触关系必须真实合理。', ''),
    # 收尾的通用语
    (r'(?:接触点贴合无穿透|所有接触面贴合无穿透|接触点必须有清晰可见的接触边界)[^。]*?。', ''),
    # 重复连续标点清理
    (r';\s*;', ';'),
    (r'。\s*。', '。'),
    (r'^[；。\s]+', ''),
    # 重新编号：把 1)2)3)... 错乱的序号简化（最简单做法：保留原始序号文本，模型能读懂）
]

def condense(s: str, rules: list[tuple[str, str]]) -> str:
    out = s
    for pattern, repl in rules:
        out = re.sub(pattern, repl, out)
    # 清理双空格、首尾空白
    out = re.sub(r'\s+', ' ', out).strip()
    return out

# 字符串匹配：subjectLock: '...' 或 negative: '...'
SUBJECT_LOCK_RE = re.compile(r"subjectLock\s*:\s*'((?:[^'\\]|\\.)*)'", re.DOTALL)
NEGATIVE_RE = re.compile(r"negative\s*:\s*'((?:[^'\\]|\\.)*)'", re.DOTALL)

def process_file(p: Path) -> tuple[int, int]:
    """精简一个 roots 文件，返回 (原字符数, 精简后字符数)。"""
    txt = p.read_text(encoding='utf-8')
    orig_len = sum(len(m.group(1)) for m in SUBJECT_LOCK_RE.finditer(txt)) + \
               sum(len(m.group(1)) for m in NEGATIVE_RE.finditer(txt))

    def repl_lock(m):
        new = condense(m.group(1), SUBJECT_LOCK_DELETIONS)
        return f"subjectLock: '{new}'"
    def repl_neg(m):
        new = condense(m.group(1), NEGATIVE_DELETIONS)
        return f"negative: '{new}'"

    new_txt = SUBJECT_LOCK_RE.sub(repl_lock, txt)
    new_txt = NEGATIVE_RE.sub(repl_neg, new_txt)

    new_len = sum(len(m.group(1)) for m in SUBJECT_LOCK_RE.finditer(new_txt)) + \
              sum(len(m.group(1)) for m in NEGATIVE_RE.finditer(new_txt))

    if new_txt != txt:
        p.write_text(new_txt, encoding='utf-8')
    return orig_len, new_len

def main():
    if not ROOTS_DIR.is_dir():
        print(f"ERR: {ROOTS_DIR} 不存在", file=sys.stderr)
        sys.exit(1)

    # 已手工精简过的文件跳过（避免二次压缩破坏知识）
    skip = {'zhu-zhai-jia-ju.js', 'jia-zhuang-zhu-cai.js'}

    files = sorted(p for p in ROOTS_DIR.glob('*.js') if p.name not in skip)
    print(f"发现 {len(files)} 个 roots 文件待精简（已跳过 {len(skip)} 个手工处理过的）")
    print()

    total_orig, total_new = 0, 0
    for p in files:
        orig, new = process_file(p)
        total_orig += orig
        total_new += new
        if orig != new:
            saved = orig - new
            pct = saved * 100 / orig if orig else 0
            print(f"  {p.name:50s}  {orig:4d} → {new:4d}  (-{saved:3d}, -{pct:.0f}%)")
        else:
            print(f"  {p.name:50s}  {orig:4d} 字  (无变化)")

    if total_orig:
        saved_total = total_orig - total_new
        pct_total = saved_total * 100 / total_orig
        print()
        print(f"合计：{total_orig} → {total_new}  (-{saved_total}, -{pct_total:.0f}%)")
    print()
    print("接下来跑 `node --check` 逐个验证语法。")

if __name__ == '__main__':
    main()
