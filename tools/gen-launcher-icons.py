"""生成 Android launcher icon 資產（品牌 = 「Macau POS」logo）。

─────────────────────────────────────────────────────────────
點解有呢支腳本
─────────────────────────────────────────────────────────────
App 的 launcher icon 唔應該係「無人知點嚟」的二進位檔。呢支腳本由**單一品牌原圖**
推導出全部 mipmap 資產，日後品牌更新只需換 SRC 再跑一次。

品牌原圖來源 = `C:\\dev\\desktop-companion\\electron\\icon.png`
（「Macau POS Desktop」的 app icon：紫色圓角底 + 白色單據 + 綠色剔號）。
⚠️ 網頁 POS 的 `src/app/favicon.ico` **唔係**品牌 logo（係 Vercel 預設圖），唔好用。

─────────────────────────────────────────────────────────────
跑法
─────────────────────────────────────────────────────────────
    <venv>/Scripts/python.exe tools/gen-launcher-icons.py [品牌原圖路徑]
需要 Pillow（本機已裝喺 `C:\\Users\\surface\\.workbuddy\\binaries\\python\\envs\\default`）。

─────────────────────────────────────────────────────────────
產出（app/src/main/res/）
─────────────────────────────────────────────────────────────
    mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png          48dp 系列（legacy）
    mipmap-{...}/ic_launcher_round.png                               同上（圓形 launcher 用）
    mipmap-{...}/ic_launcher_background.png                          108dp 系列，紫漸變
    mipmap-{...}/ic_launcher_foreground.png                          108dp 系列，單據（已 key 走紫）
    mipmap-anydpi-v26/ic_launcher.xml + ic_launcher_round.xml        adaptive icon 定義

─────────────────────────────────────────────────────────────
兩個關鍵設計決定（改之前先讀）
─────────────────────────────────────────────────────────────
1. **背景用漸變，唔用純色**：原圖紫色係對角漸變（左上偏藍 → 右下偏洋紅）。
   純色背景會令前景同背景之間出現色差接縫。
2. **前景只留單據，並加內縮遮罩**：紫底要 key 走（否則 adaptive 會出現「盒中盒」）；
   而貼圖圓角邊緣的半透明像素顏色被稀釋、紫色判定抓唔到，會殘留一條淡淡圓角弧線
   → 故再套一個「內縮 40px 的圓角矩形」遮罩（見 INSET / RADIUS）。
"""
from PIL import Image, ImageDraw
import os
import sys

DEFAULT_SRC = r'C:\dev\desktop-companion\electron\icon.png'
RES = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    '..', 'app', 'src', 'main', 'res'))
SRC = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC

# 密度表：legacy 用 48dp 基準；adaptive 用 108dp 畫布（可見安全區 = 72/108 = 66.7%）
LEGACY = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
ADAPT = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}
FG_SCALE = 0.62          # 前景（單據）佔 108dp 畫布的比例；< 安全區 0.667 留呼吸空間
INSET, RADIUS = 40, 96   # 前景遮罩：內縮 px 與圓角半徑（切走貼圖圓角殘影）

im = Image.open(SRC).convert('RGBA')
W, H = im.size
px = im.load()
print('品牌原圖: %s  (%dx%d)' % (SRC, W, H))

# ── 貼圖四角紫（內縮 70px 避開圓角透明區）──
TL, TR = px[70, 70][:3], px[W - 70, 70][:3]
BL, BR = px[70, H - 70][:3], px[W - 70, H - 70][:3]
print('四角紫: TL=#%02X%02X%02X TR=#%02X%02X%02X BL=#%02X%02X%02X BR=#%02X%02X%02X' % (*TL, *TR, *BL, *BR))


def gradient(size):
    """對角雙線性漸變，還原原圖紫調（避免純色背景的接縫）。"""
    img = Image.new('RGB', (size, size))
    d = img.load()
    for y in range(size):
        v = y / (size - 1)
        for x in range(size):
            u = x / (size - 1)
            top = [TL[i] + (TR[i] - TL[i]) * u for i in range(3)]
            bot = [BL[i] + (BR[i] - BL[i]) * u for i in range(3)]
            d[x, y] = tuple(int(top[i] + (bot[i] - top[i]) * v) for i in range(3))
    return img


def is_purple(r, g, b):
    return b > 150 and b - g > 60 and r < 200 and g < 150


# ── 前景：key 走紫，只留單據 ──
fg_master = im.copy()
fp = fg_master.load()
for y in range(H):
    for x in range(W):
        r, g, b, a = fp[x, y]
        if a and is_purple(r, g, b):
            fp[x, y] = (r, g, b, 0)

_mask = Image.new('L', (W, H), 0)
ImageDraw.Draw(_mask).rounded_rectangle(
    (INSET, INSET, W - 1 - INSET, H - 1 - INSET), radius=RADIUS, fill=255)
fg_master.putalpha(Image.composite(fg_master.split()[3], Image.new('L', (W, H), 0), _mask))
print('前景遮罩 bbox: %s（內縮 %dpx，半徑 %d）' % (str(_mask.getbbox()), INSET, RADIUS))

# ── legacy icons ──
made = []
for dens, sz in LEGACY.items():
    d = os.path.join(RES, 'mipmap-' + dens)
    os.makedirs(d, exist_ok=True)
    t = im.resize((sz, sz), Image.LANCZOS)
    t.save(os.path.join(d, 'ic_launcher.png'))
    t.save(os.path.join(d, 'ic_launcher_round.png'))
    made.append('mipmap-%s/ic_launcher.png + ic_launcher_round.png  (%dx%d)' % (dens, sz, sz))

# ── adaptive icons ──
for dens, sz in ADAPT.items():
    d = os.path.join(RES, 'mipmap-' + dens)
    os.makedirs(d, exist_ok=True)
    gradient(sz).save(os.path.join(d, 'ic_launcher_background.png'))
    s = round(sz * FG_SCALE)
    fg = fg_master.resize((s, s), Image.LANCZOS)
    canvas = Image.new('RGBA', (sz, sz), (0, 0, 0, 0))
    canvas.alpha_composite(fg, ((sz - s) // 2, (sz - s) // 2))
    canvas.save(os.path.join(d, 'ic_launcher_foreground.png'))
    made.append('mipmap-%s/ic_launcher_background.png + ic_launcher_foreground.png  (%dx%d)' % (dens, sz, sz))

xml_dir = os.path.join(RES, 'mipmap-anydpi-v26')
os.makedirs(xml_dir, exist_ok=True)
XML = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
'''
for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
    with open(os.path.join(xml_dir, name), 'w', encoding='utf-8') as f:
        f.write(XML)
made.append('mipmap-anydpi-v26/ic_launcher.xml + ic_launcher_round.xml')

print('\n已生成 %d 組到 %s：' % (len(made), RES))
for m in made:
    print('  ' + m)
print('\n記得 AndroidManifest 要有：')
print('  android:icon="@mipmap/ic_launcher"')
print('  android:roundIcon="@mipmap/ic_launcher_round"')
