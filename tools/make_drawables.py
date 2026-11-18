"""Generates vector drawables for the Lev browser (icons, wallpapers, launcher icon)
plus SVG previews of everything so the art can be checked visually."""
import json
import os
import sys

RES = sys.argv[1]           # app/src/main/res
PREVIEW = sys.argv[2]       # folder for svg previews
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lion_facets  # noqa: E402  — геометрический лев (грани)
os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
os.makedirs(PREVIEW, exist_ok=True)

NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'
AAPT = 'xmlns:aapt="http://schemas.android.com/aapt"'


def write(name, xml):
    with open(os.path.join(RES, "drawable", name + ".xml"), "w") as f:
        f.write(xml)


def svg_preview(name, w, h, body):
    with open(os.path.join(PREVIEW, name + ".svg"), "w") as f:
        f.write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w * 4}" height="{h * 4}">'
                f'<rect width="{w}" height="{h}" fill="#222"/>{body}</svg>')


# ------------------------------------------------------------------ icons (24dp)
# (name, [(pathData, mode)]) — mode "s" stroke 1.9, "f" fill
ICONS = {
    "ic_back": [("M15,5 L8,12 L15,19", "s")],
    "ic_forward": [("M9,5 L16,12 L9,19", "s")],
    "ic_plus": [("M12,5 L12,19 M5,12 L19,12", "s")],
    "ic_close": [("M6.5,6.5 L17.5,17.5 M17.5,6.5 L6.5,17.5", "s")],
    "ic_menu": [("M5,10.4 a1.6,1.6 0 1,0 0.01,0 Z M12,10.4 a1.6,1.6 0 1,0 0.01,0 Z M19,10.4 a1.6,1.6 0 1,0 0.01,0 Z", "f")],
    "ic_reload": [("M19,12 A7,7 0 1,1 16.95,7.05", "s"), ("M17.2,3.6 L17.2,7.6 L13.2,7.6", "s")],
    "ic_translate": [("M3.5,6 L12.5,6 M8,4 L8,6 M5.6,6 C6.6,9.6 9,12.2 12,13.4 M10.4,6 C9.4,10 7,12.8 4,14", "s"),
                     ("M12.8,20 L16.5,10.8 L20.2,20 M14.1,17 L18.9,17", "s")],
    "ic_shield": [("M12,3 L19,6 L19,11.2 C19,15.6 16,19 12,21 C8,19 5,15.6 5,11.2 L5,6 Z", "s"),
                  ("M9,12 L11.2,14.2 L15.2,10", "s")],
    "ic_shield_off": [("M12,3 L19,6 L19,11.2 C19,15.6 16,19 12,21 C8,19 5,15.6 5,11.2 L5,6 Z", "s"),
                      ("M4,4 L20,20", "s")],
    "ic_incognito": [("M3,12.5 L21,12.5 M6,12.5 L7.6,6.5 C7.9,5.4 9,5 10,5.4 L12,6.2 L14,5.4 C15,5 16.1,5.4 16.4,6.5 L18,12.5", "s"),
                     ("M10.2,17.5 a2.6,2.6 0 1,1 -5.2,0 a2.6,2.6 0 1,1 5.2,0 Z M19,17.5 a2.6,2.6 0 1,1 -5.2,0 a2.6,2.6 0 1,1 5.2,0 Z M10.2,17 C11.4,16.2 12.6,16.2 13.8,17", "s")],
    "ic_download": [("M12,4 L12,15 M7.5,10.5 L12,15 L16.5,10.5 M5,20 L19,20", "s")],
    "ic_screenshot": [("M5,8.5 L5,5 L8.5,5 M15.5,5 L19,5 L19,8.5 M19,15.5 L19,19 L15.5,19 M8.5,19 L5,19 L5,15.5", "s"),
                      ("M12,8.5 L12,15.5 M9.5,13 L12,15.5 L14.5,13", "s")],
    "ic_home": [("M4,11 L12,4.5 L20,11 M6.5,9.5 L6.5,19.5 L17.5,19.5 L17.5,9.5", "s")],
    "ic_tabs": [("M8,4.5 L18,4.5 Q19.5,4.5 19.5,6 L19.5,16", "s"),
                ("M6,7.5 L15,7.5 Q16.5,7.5 16.5,9 L16.5,18 Q16.5,19.5 15,19.5 L6,19.5 Q4.5,19.5 4.5,18 L4.5,9 Q4.5,7.5 6,7.5 Z", "s")],
    "ic_search": [("M16.5,10.5 a6,6 0 1,1 -12,0 a6,6 0 1,1 12,0 Z M15,15 L20,20", "s")],
    "ic_play": [("M8,5.5 L18.5,12 L8,18.5 Z", "s")],
    "ic_info": [("M20.5,12 a8.5,8.5 0 1,1 -17,0 a8.5,8.5 0 1,1 17,0 Z M12,11 L12,16.5", "s"),
                ("M11,7.8 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0 Z", "f")],
    "ic_chevron": [("M10,6 L16,12 L10,18", "s")],
    "ic_theme": [("M20,12 A8,8 0 1,1 4,12 A8,8 0 1,1 20,12 Z", "s"), ("M12,4 A8,8 0 0,1 12,20 Z", "f")],
    "ic_trash": [("M4.5,7 L19.5,7 M9.5,7 L9.5,4.5 L14.5,4.5 L14.5,7 M6.5,7 L7.5,20 L16.5,20 L17.5,7 M10,11 L10,16.5 M14,11 L14,16.5", "s")],
    "ic_settings": [("M4,7 L20,7 M4,17 L20,17", "s"),
                    ("M10.5,7 a2.5,2.5 0 1,1 -5,0 a2.5,2.5 0 1,1 5,0 Z M18.5,17 a2.5,2.5 0 1,1 -5,0 a2.5,2.5 0 1,1 5,0 Z", "sfill")],
    # notification (solid)
    "ic_n_play": [("M8,5 L19,12 L8,19 Z", "f")],
    "ic_n_pause": [("M6.5,5 L10,5 L10,19 L6.5,19 Z M14,5 L17.5,5 L17.5,19 L14,19 Z", "f")],
    "ic_n_next": [("M5,5 L14,12 L5,19 Z M15.5,5 L18.5,5 L18.5,19 L15.5,19 Z", "f")],
    "ic_n_prev": [("M19,5 L10,12 L19,19 Z M8.5,5 L5.5,5 L5.5,19 L8.5,19 Z", "f")],
}

for name, parts in ICONS.items():
    xml = [f'<vector {NS} android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">']
    svg = []
    for d, mode in parts:
        if mode == "f":
            xml.append(f'  <path android:fillColor="#FFFFFFFF" android:pathData="{d}" />')
            svg.append(f'<path d="{d}" fill="#fff"/>')
        else:
            fill = ' android:fillColor="#FF000000"' if mode == "sfill" else ""
            xml.append(f'  <path android:pathData="{d}" android:strokeColor="#FFFFFFFF" android:strokeWidth="1.9"'
                       f' android:strokeLineCap="round" android:strokeLineJoin="round"{fill} />')
            svg.append(f'<path d="{d}" fill="{"#222" if mode == "sfill" else "none"}" stroke="#fff" stroke-width="1.9" '
                       f'stroke-linecap="round" stroke-linejoin="round"/>')
    xml.append("</vector>\n")
    write(name, "\n".join(xml))
    svg_preview(name, 24, 24, "".join(svg))

# ------------------------------------------------------------- wallpapers 360x800
W, H = 360, 800


def grad(c1, c2, y1=0, y2=H):
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="0" '
            f'android:startY="{y1}" android:endX="0" android:endY="{y2}" android:startColor="{c1}" '
            f'android:endColor="{c2}" /></aapt:attr>')


def wallpaper(name, bg1, bg2, layers):
    xml = [f'<vector {NS} {AAPT} android:width="360dp" android:height="800dp" '
           f'android:viewportWidth="{W}" android:viewportHeight="{H}">',
           f'  <path android:pathData="M0,0 L{W},0 L{W},{H} L0,{H} Z">{grad(bg1, bg2)}</path>']
    svg = [f'<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#{bg1[3:]}"/>'
           f'<stop offset="1" stop-color="#{bg2[3:]}"/></linearGradient></defs><rect width="{W}" height="{H}" fill="url(#g)"/>']
    for d, color, stroke in layers:
        if stroke:
            xml.append(f'  <path android:pathData="{d}" android:strokeColor="{color}" android:strokeWidth="{stroke}" />')
            svg.append(f'<path d="{d}" fill="none" stroke="#{color[3:]}" stroke-opacity="{int(color[1:3], 16) / 255:.2f}" stroke-width="{stroke}"/>')
        else:
            xml.append(f'  <path android:pathData="{d}" android:fillColor="{color}" />')
            svg.append(f'<path d="{d}" fill="#{color[3:]}" fill-opacity="{int(color[1:3], 16) / 255:.2f}"/>')
    xml.append("</vector>\n")
    write(name, "\n".join(xml))
    svg_preview(name, W, H, "".join(svg))


wallpaper("wp_mountains", "#FF17181B", "#FF040405", [
    ("M268,170 a26,26 0 1,0 52,0 a26,26 0 1,0 -52,0 Z", "#FF2B2C31", None),
    ("M0,520 L58,468 L104,498 L176,420 L236,480 L300,436 L360,474 L360,800 L0,800 Z", "#FF1C1D21", None),
    ("M0,600 L66,540 L128,586 L200,516 L268,592 L326,548 L360,566 L360,800 L0,800 Z", "#FF131417", None),
    ("M0,690 C80,648 140,702 222,660 C282,630 330,672 360,652 L360,800 L0,800 Z", "#FF09090B", None),
])
wallpaper("wp_dunes", "#FF1B1B1E", "#FF070708", [
    ("M0,520 C110,470 210,600 360,520 L360,800 L0,800 Z", "#FF151517", None),
    ("M0,610 C120,560 230,690 360,610 L360,800 L0,800 Z", "#FF101012", None),
    ("M0,700 C100,670 250,760 360,700 L360,800 L0,800 Z", "#FF0A0A0B", None),
    ("M0,520 C110,470 210,600 360,520", "#FF26262A", 1.2),
    ("M0,610 C120,560 230,690 360,610", "#FF1E1E22", 1.0),
])
wallpaper("wp_waves", "#FF0E0E10", "#FF050506", [
    ("M0,430 C90,400 150,470 240,440 C300,420 330,430 360,420 L360,800 L0,800 Z", "#FF131316", None),
    ("M0,520 C80,490 170,560 250,525 C310,500 340,515 360,505 L360,800 L0,800 Z", "#FF17171B", None),
    ("M0,610 C100,580 180,650 270,615 C320,595 345,605 360,600 L360,800 L0,800 Z", "#FF1C1C21", None),
    ("M0,700 C90,675 190,735 280,705 C320,692 345,698 360,695 L360,800 L0,800 Z", "#FF222227", None),
])
rings = []
for i, r in enumerate(range(40, 520, 34)):
    a = max(0x10, 0x3A - i * 3)
    rings.append((f"M{300 - r},150 a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0", f"#{a:02X}FFFFFF", 1.0))
wallpaper("wp_rings", "#FF0B0B0C", "#FF050505", rings)

# --- ещё фоны: минимализм, чёрное и серое
moon = [("M196,210 a64,64 0 1,0 128,0 a64,64 0 1,0 -128,0 Z", "#FF2A2A2E", None)]
for i, r in enumerate((84, 108, 138)):
    moon.append((f"M{260 - r},210 a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0", f"#{0x22 - i * 8:02X}FFFFFF", 1.0))
moon.append(("M0,690 C90,660 200,700 360,668 L360,800 L0,800 Z", "#FF0C0C0E", None))
wallpaper("wp_moon", "#FF101013", "#FF030303", moon)

fog = []
for i in range(7):
    y = 300 + i * 70
    fog.append((f"M0,{y} C90,{y - 26} 200,{y + 30} 360,{y - 8} L360,{y + 60} C220,{y + 80} 120,{y + 40} 0,{y + 66} Z",
                f"#{0x0C + i * 2:02X}FFFFFF", None))
wallpaper("wp_fog", "#FF131316", "#FF050506", fog)

lines = []
for i in range(-14, 30):
    x = i * 26
    lines.append((f"M{x},800 L{x + 360},0", f"#{0x12 if i % 3 else 0x22:02X}FFFFFF", 1.0))
wallpaper("wp_lines", "#FF0E0E10", "#FF050506", lines)

dots = []
d = ""
for row in range(30):
    for colm in range(14):
        cx, cy = 18 + colm * 25.2 + (12.6 if row % 2 else 0), 20 + row * 27
        if cx > 356:
            continue
        rr = 1.1 + 0.9 * (row / 30.0)
        d += f"M{cx - rr:.1f},{cy} a{rr:.1f},{rr:.1f} 0 1,0 {2 * rr:.1f},0 a{rr:.1f},{rr:.1f} 0 1,0 {-2 * rr:.1f},0 Z "
dots.append((d.strip(), "#30FFFFFF", None))
wallpaper("wp_dots", "#FF0D0D0F", "#FF040404", dots)

wallpaper("wp_horizon", "#FF050506", "#FF151518", [
    ("M-200,560 a380,380 0 0,1 760,0 L560,800 L-200,800 Z", "#FF0A0A0C", None),
    ("M-200,560 a380,380 0 0,1 760,0", "#40FFFFFF", 1.2),
    ("M-160,600 a340,340 0 0,1 680,0", "#16FFFFFF", 1.0),
])

import random  # noqa: E402
random.seed(7)
stars = ""
for _ in range(90):
    cx, cy, rr = random.uniform(0, 360), random.uniform(0, 620), random.choice((0.6, 0.8, 1.0, 1.4))
    stars += f"M{cx - rr:.1f},{cy:.1f} a{rr},{rr} 0 1,0 {2 * rr},0 a{rr},{rr} 0 1,0 {-2 * rr},0 Z "
wallpaper("wp_stars", "#FF07070A", "#FF121216", [
    (stars.strip(), "#AAFFFFFF", None),
    ("M0,700 L70,650 L130,690 L210,630 L290,690 L360,660 L360,800 L0,800 Z", "#FF0B0B0D", None),
])

prism = []
pts = [(0, 0), (360, 0), (360, 800), (0, 800)]
tri = [((0, 0), (220, 0), (90, 330)), ((220, 0), (360, 0), (360, 260)), ((220, 0), (360, 260), (90, 330)),
       ((0, 0), (90, 330), (0, 460)), ((90, 330), (360, 260), (260, 560)), ((0, 460), (90, 330), (260, 560)),
       ((360, 260), (360, 640), (260, 560)), ((0, 460), (260, 560), (120, 800)), ((0, 460), (120, 800), (0, 800)),
       ((260, 560), (360, 640), (360, 800)), ((260, 560), (360, 800), (120, 800))]
shades = [0x17, 0x1D, 0x12, 0x0F, 0x15, 0x0B, 0x19, 0x09, 0x06, 0x0D, 0x08]
for t, s in zip(tri, shades):
    prism.append((f"M{t[0][0]},{t[0][1]} L{t[1][0]},{t[1][1]} L{t[2][0]},{t[2][1]} Z", f"#FF{s:02X}{s:02X}{s + 2:02X}", None))
wallpaper("wp_prism", "#FF0A0A0B", "#FF050505", prism)

# ------------------------------------------------------------- launcher (lion)
# Геометрический лев на чисто чёрном фоне: золотой и белый (переключаются в настройках).
def launcher(suffix, pal):
    parts = lion_facets.facets(pal)
    head = (f'<vector {NS} android:width="108dp" android:height="108dp" android:viewportWidth="108" '
            'android:viewportHeight="108">\n'
            # 100×100 рисунок → безопасная зона значка (≈66dp в центре), чуть крупнее для читаемости
            '  <group android:translateX="17" android:translateY="15.5" android:scaleX="0.74" android:scaleY="0.74">')
    fg, mono = [head], [head]
    for d, col in parts:
        fg.append(f'    <path android:fillColor="#FF{col[1:]}" android:strokeColor="#FF000000" '
                  f'android:strokeWidth="0.85" android:strokeLineJoin="round" android:pathData="{d}" />')
        light = col != "#000000"
        mono.append(f'    <path android:fillColor="{"#FFFFFFFF" if light else "#FF000000"}" '
                    f'android:strokeColor="#FF000000" android:strokeWidth="0.85" android:pathData="{d}" />')
    fg.append("  </group>\n</vector>\n")
    mono.append("  </group>\n</vector>\n")
    write("ic_launcher_fg" + suffix, "\n".join(fg))
    write("ic_launcher_mono" + suffix, "\n".join(mono))
    with open(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher" + suffix + ".xml"), "w") as f:
        f.write(f'<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon {NS}>\n'
                '    <background android:drawable="@color/icon_bg" />\n'
                f'    <foreground android:drawable="@drawable/ic_launcher_fg{suffix}" />\n'
                f'    <monochrome android:drawable="@drawable/ic_launcher_mono{suffix}" />\n'
                '</adaptive-icon>\n')


launcher("_lion_gold", lion_facets.GOLD)    # гранёные львы — на выбор в настройках
launcher("_lion_white", lion_facets.WHITE)


# ------------------------------------------------------------- launcher (лев на щите)
# Рисунок владельца (лев на щите), переведённый в вектор: tools/lion_shield.txt —
# по одному куску контура на строку (у каждого куска свои дыры, поэтому evenOdd).
def shield_paths():
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "lion_shield.txt")) as f:
        return [ln.strip() for ln in f if ln.strip()]


def shield(suffix, gold):
    paths = shield_paths()
    aapt = ' xmlns:aapt="http://schemas.android.com/aapt"' if gold else ''
    head = (f'<?xml version="1.0" encoding="utf-8"?>\n<vector {NS}{aapt} android:width="108dp" '
            'android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n')
    fg, mono = [head], [head]
    for d in paths:
        if gold:
            fg.append('  <path android:fillType="evenOdd" android:pathData="' + d + '">\n'
                      '    <aapt:attr name="android:fillColor">\n'
                      '      <gradient android:type="linear" android:startX="54" android:startY="20" '
                      'android:endX="54" android:endY="88">\n'
                      '        <item android:offset="0" android:color="#FFFFE7A3" />\n'
                      '        <item android:offset="0.5" android:color="#FFE2B45C" />\n'
                      '        <item android:offset="1" android:color="#FFA8701F" />\n'
                      '      </gradient>\n    </aapt:attr>\n  </path>\n')
        else:
            fg.append('  <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" '
                      'android:pathData="' + d + '" />\n')
        mono.append('  <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" '
                    'android:pathData="' + d + '" />\n')
    fg.append('</vector>\n')
    mono.append('</vector>\n')
    write("ic_launcher_fg" + suffix, "".join(fg))
    if not gold:
        write("ic_launcher_mono", "".join(mono))  # один монохромный на оба цвета
    with open(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher" + suffix + ".xml"), "w") as f:
        f.write(f'<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon {NS}>\n'
                '    <background android:drawable="@color/icon_bg" />\n'
                f'    <foreground android:drawable="@drawable/ic_launcher_fg{suffix}" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_mono" />\n'
                '</adaptive-icon>\n')


shield("", False)               # по умолчанию — белый лев на щите
shield("_shield_gold", True)

# пустой значок для заставки при запуске (Android 12+): ни один «чужой» значок не мелькает
write("splash_blank", f'<?xml version="1.0" encoding="utf-8"?>\n<shape {NS} android:shape="rectangle">\n'
      '    <solid android:color="#00000000" />\n    <size android:width="1dp" android:height="1dp" />\n</shape>\n')
print("drawables:", len(ICONS) + 11 + 7)
