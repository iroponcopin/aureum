#!/usr/bin/env python3
"""Aureum の MOD アイコン(128x128)を決定的に生成する。

金(aurum)を名に持つ軽量化 MOD なので、深い夜色の丸角地に
金のインゴット断面を思わせる菱形と上向きの軽さの線、を最小の幾何で描く。
再生成: python3 tools/gen_icon.py
"""
from PIL import Image, ImageDraw

SIZE = 128
img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

# 丸角の地: 夜のラピス色 → わずかに明るい下端のグラデーション。
BG_TOP = (24, 26, 38, 255)
BG_BOTTOM = (36, 40, 58, 255)
RADIUS = 22
for y in range(SIZE):
    t = y / (SIZE - 1)
    colour = tuple(int(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOTTOM))
    draw.line([(0, y), (SIZE, y)], fill=colour)
# 丸角マスク
mask = Image.new("L", (SIZE, SIZE), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], RADIUS, fill=255)
img.putalpha(mask)
draw = ImageDraw.Draw(img)

GOLD_DEEP = (176, 128, 32, 255)
GOLD = (232, 180, 66, 255)
GOLD_LIGHT = (255, 224, 130, 255)

cx, cy = SIZE // 2, SIZE // 2 + 6
w, h = 40, 26

# 影(重さが抜けた跡)
draw.polygon([(cx - w, cy + 10), (cx, cy + h + 10), (cx + w, cy + 10), (cx, cy - h + 10)],
             fill=(0, 0, 0, 70))
# 金の菱形(インゴット断面)
draw.polygon([(cx - w, cy), (cx, cy + h), (cx + w, cy), (cx, cy - h)], fill=GOLD)
draw.polygon([(cx - w, cy), (cx, cy - h), (cx + w, cy), (cx, cy - h + 12)], fill=GOLD_LIGHT)
draw.polygon([(cx - w, cy), (cx, cy + h), (cx + w, cy), (cx, cy + h - 8)], fill=GOLD_DEEP)

# 上へ抜ける 3 本の軽さの線(中央が最長)。
for dx, top in ((-22, 44), (0, 26), (22, 44)):
    draw.line([(cx + dx, cy - h - 6), (cx + dx, top)], fill=GOLD_LIGHT, width=4)
    draw.polygon([(cx + dx - 5, top + 6), (cx + dx + 5, top + 6), (cx + dx, top - 4)],
                 fill=GOLD_LIGHT)

OUT = __file__.rsplit("/tools/", 1)[0] + "/src/main/resources/assets/aureum/icon.png"
img.save(OUT)
print("wrote", OUT)
