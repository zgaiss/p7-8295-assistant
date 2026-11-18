# -*- coding: utf-8 -*-
"""重新生成仪表端中文字体子集 cjk.ttf。

用法（在 instrument/ 目录下）：  python gen_cjk.py

原理：仪表端只渲染十几个中文标签，不需要完整的 8.6MB 普惠体，
      从阿里巴巴普惠体里子集化出「真正会画到屏幕上」的那几个字，
      既能对齐原版字形，又把字体压到 ~10KB，适配嵌入式内存。

流程：
  1. 明确渲染文案清单 LABELS（语义化，方便人读）
  2. 自动扫描 *.go 里 drawText/drawBottom/drawCard/drawTemp 与 seg{...} 传入的中文，
     与清单交叉校验 —— 源码里若出现清单外的新中文，自动并入并告警，避免漏字
  3. 从 Alibaba-PuHuiTi-Regular.ttf 子集化出 cjk.ttf（go:embed 进二进制）
"""
import re
import glob
import os

from fontTools import subset

SRC_FONT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "..", "ref", "p7assist", "wj", "Alibaba-PuHuiTi-Regular.ttf")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fonts", "cjk.ttf")

# ---- 渲染文案清单（= 屏幕上一共会显示的中文标签）----
LABELS = [
    "电瓶",              # 左下角：蓄电池电压标签
    "车内",              # 左下角：车内温度标签
    "里程",              # 底部中央行程段
    "平均",              # 底部中央行程段
    "剩余里程 / 时间",   # 导航卡片 1 标题
    "到达时间",          # 导航卡片 2 标题
    "℃",                # 胎温单位（摄氏）
]


def is_render_char(ch):
    """一个字符是否属于「需要用 CJK 字体绘制」的字符（与 canvas.go isCJK 一致）"""
    return ord(ch) >= 0x2E80 or ch in ("℃", "·")


def collect(chars_sets):
    out = set()
    for s in chars_sets:
        for ch in s:
            if ch.strip() and is_render_char(ch):
                out.add(ch)
    return out


def scan_source():
    """扫描 *.go，提取渲染调用里的中文，用于和清单交叉校验。"""
    found = set()
    pat_draw = re.compile(r'(?:drawText|drawBottom|drawCard|drawTemp)\s*\([^)]*?"([^"]*)"')
    pat_seg = re.compile(r'seg\s*\{\s*"([^"]*)"')
    for f in glob.glob(os.path.join(os.path.dirname(os.path.abspath(__file__)), "*.go")):
        txt = open(f, encoding="utf-8").read()
        for m in pat_draw.finditer(txt):
            found |= collect([m.group(1)])
        for m in pat_seg.finditer(txt):
            found |= collect([m.group(1)])
    return found


def main():
    want = collect(LABELS)
    found = scan_source()
    missing = found - want
    if missing:
        print("⚠ 源码里出现了清单外的渲染中文，已自动并入：", "".join(sorted(missing)))
        want |= missing
    extra = want - found
    if extra:
        print("ℹ 清单里有、但源码扫描未直接命中的字符（如被注释/间接引用，保留以防万一）：",
              "".join(sorted(extra)))

    text = "".join(sorted(want))
    print("最终字符集（%d 个）：%s" % (len(text), text))

    opts = subset.Options()
    opts.layout_features = ['*']
    opts.name_IDs = ['*']
    opts.notdef_outline = True
    opts.recalc_bounds = True
    opts.drop_tables += ['DSIG']

    font = subset.load_font(SRC_FONT, opts)
    sub = subset.Subsetter(options=opts)
    sub.populate(text=text)
    sub.subset(font)
    subset.save_font(font, OUT, opts)
    print("已生成", OUT, os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
