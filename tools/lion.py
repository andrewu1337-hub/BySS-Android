"""Lion head v2: flowing flame mane (silver strands with black separations),
silver face with black engraved features. 0..100 box; mirrored left/right."""
import json
import math
import sys

OUT = sys.argv[1]


def rot(v, deg):
    a = math.radians(deg)
    return (v[0] * math.cos(a) - v[1] * math.sin(a), v[0] * math.sin(a) + v[1] * math.cos(a))


def add(a, b, k=1.0):
    return (a[0] + b[0] * k, a[1] + b[1] * k)


def f(p):
    return f"{p[0]:.2f},{p[1]:.2f}"


def strand(cx, cy, rx, ry, ang, length, width, droop, curl):
    """Flame strand rooted on an ellipse around the face. ang: 0=up, clockwise."""
    a = math.radians(ang)
    base = (cx + rx * math.sin(a), cy - ry * math.cos(a))
    n = (math.sin(a), -math.cos(a))
    side = 1 if math.sin(a) >= 0 else -1
    d = rot(n, droop * side)              # droop the strand outward-down
    d2 = rot(d, curl * side)              # extra curl near the tip
    perp = (-d[1], d[0])
    bl = add(base, perp, -width / 2)
    br = add(base, perp, width / 2)
    tip = add(base, d2, length)
    c1 = add(add(bl, d, length * 0.42), perp, -width * 0.30)
    c2 = add(add(tip, d2, -length * 0.30), perp, -width * 0.18)
    c3 = add(add(tip, d2, -length * 0.50), perp, width * 0.40)
    c4 = add(add(br, d, length * 0.30), perp, width * 0.22)
    return f"M{f(bl)} C{f(c1)} {f(c2)} {f(tip)} C{f(c3)} {f(c4)} {f(br)} Z"


def mane(cx, cy, rx, ry, angles, lens, width, droop_fn, curl):
    parts = []
    for ang, L in zip(angles, lens):
        parts.append(strand(cx, cy, rx, ry, ang, L, width, droop_fn(ang), curl))
    return " ".join(parts)


def sym(angles_right):
    """Right-side angles (0..180) -> both sides, symmetric."""
    out = []
    for a in angles_right:
        out.append(a)
        if 0 < a < 180:
            out.append(-a)
    return out


def droop(a):
    a = abs(a)
    if a < 25:
        return 4
    if a < 90:
        return 10 + (a - 25) * 0.25
    return 26


# --- mane layers ---------------------------------------------------------------
CX, CY = 50, 52
back_r = [0, 22.5, 45, 67.5, 90, 112.5, 135, 157.5, 180]
back_l = [22, 24, 27, 30, 32, 32, 30, 28, 27]
mid_r = [11.25, 33.75, 56.25, 78.75, 101.25, 123.75, 146.25, 168.75]
mid_l = [17, 18, 21, 23, 24, 23, 21, 20]
front_r = [0, 30, 60, 90, 120, 150, 180]
front_l = [11, 12, 14, 15, 15, 14, 13]


def layer(ang_r, len_r, rx, ry, width, curl):
    angs, lens = [], []
    for a, L in zip(ang_r, len_r):
        angs.append(a)
        lens.append(L)
        if 0 < a < 180:
            angs.append(-a)
            lens.append(L)
    return mane(CX, CY, rx, ry, angs, lens, width, droop, curl)


paths = {}
paths["mane_back"] = layer(back_r, back_l, 15, 20, 17, 24)
paths["mane_mid"] = layer(mid_r, mid_l, 15, 20, 16, 22)
paths["mane_front"] = layer(front_r, front_l, 15, 20, 14, 18)
paths["mane_core"] = ("M50,26 C61,26 68,34 68,47 C68,62 61,80 50,85 "
                      "C39,80 32,62 32,47 C32,34 39,26 50,26 Z")


def mx(path):
    out = []
    for t in path.split():
        cmd = ""
        if t[0].isalpha():
            cmd, t = t[0], t[1:]
        if "," in t:
            x, y = t.split(",")
            out.append(f"{cmd}{100 - float(x):.2f},{y}")
        else:
            out.append(cmd + t)
    return " ".join(out)


def both(p):
    return p + " " + mx(p)


# Ears (silver) and inner ears (black)
ear = "M58.5,29.5 C60,23.5 66.5,21.5 69.5,26 C70,29.5 67,32.5 63,32.5 Z"
ear_in = "M61.2,28.8 C62.2,25.8 65.8,24.8 67.4,27 C67.6,28.8 66,30.6 63.6,30.6 Z"
paths["ears"] = both(ear)
paths["ears_in"] = both(ear_in)

# Face (silver)
paths["face"] = ("M50,27 C58,27 64,31 65.5,38 C67,45 65.5,52 63,59 "
                 "C60.5,66.5 56,75 50,78.5 C44,75 39.5,66.5 37,59 "
                 "C34.5,52 33,45 34.5,38 C36,31 42,27 50,27 Z")

# Black engraved features (right half mirrored)
brow = "M50.8,43.4 C54.5,40.2 59.5,38.4 64.8,39.2 L64.4,41.4 C59.6,41.2 55.2,43.2 52,46.4 Z"
eye = "M54.6,46.8 C57.2,44.8 60.8,44.1 63.4,44.7 C61.8,47.3 58.4,48.4 54.6,48 Z"
cheek_a = "M65.6,48 L62.4,51 L65.2,53.6 Z"
cheek_b = "M63.7,57.4 L60.5,60 L62.8,63.2 Z"
cheek_c = "M60.4,66 L57.6,68.2 L59.3,71.3 Z"
mouth = "M50,67 C52.2,67.4 54.6,68.8 56.4,71 L55.5,71.7 C53.9,69.9 52,68.9 50,68.6 Z"
whisk = ("M55.2,62.6 a0.7,0.7 0 1,0 1.4,0 a0.7,0.7 0 1,0 -1.4,0 Z "
         "M57.4,61.4 a0.65,0.65 0 1,0 1.3,0 a0.65,0.65 0 1,0 -1.3,0 Z "
         "M56.9,64.4 a0.6,0.6 0 1,0 1.2,0 a0.6,0.6 0 1,0 -1.2,0 Z")
bridge = "M51.5,47.6 L52.5,47.4 L54.6,56.4 L53.4,56.6 Z"
paths["features"] = " ".join([
    both(brow), both(eye), both(cheek_a), both(cheek_b), both(cheek_c), both(mouth),
    both(bridge),
    "M44.8,58 C47.4,56.2 52.6,56.2 55.2,58 C54.4,61.4 52.4,63.4 50,63.8 C47.6,63.4 45.6,61.4 44.8,58 Z",
    "M49.4,63.4 L50.6,63.4 L50.6,67.4 L49.4,67.4 Z",
    "M46.4,72.4 C48.4,74 51.6,74 53.6,72.4 L53.8,73.6 C51.8,75.6 48.2,75.6 46.2,73.6 Z",
    # forehead fur
    "M49.45,29.5 L50.55,29.5 L50.45,38 L49.55,38 Z",
])
paths["whiskers"] = whisk + " " + mx_whisk if False else None

# mirror circles manually (arcs are relative, only the M point moves)
wl = []
for part in whisk.split(" Z"):
    part = part.strip()
    if not part:
        continue
    m = part.split()[0]
    x, y = m[1:].split(",")
    r = float(part.split()[1][1:].split(",")[0])
    wl.append(part + " Z")
    nx = 100 - float(x) - 2 * r
    wl.append(f"M{nx:.2f},{y} " + " ".join(part.split()[1:]) + " Z")
paths["whiskers"] = " ".join(wl)
paths["forehead"] = both("M53.4,30.2 L54.4,30.6 L53,37.2 L52.1,36.9 Z")

COLORS = {
    "mane_back": "#6C6C72", "mane_mid": "#A9A9AF", "mane_front": "#DCDCE1",
    "mane_core": "#DCDCE1", "ears": "#8E8E94", "ears_in": "#2C2C30",
    "face": "#ECECF0", "features": "#0A0A0B", "whiskers": "#0A0A0B", "forehead": "#9A9AA0",
}
STROKE = {"mane_back": 1.1, "mane_mid": 1.1, "mane_front": 1.1}
ORDER = ["mane_back", "mane_mid", "mane_front", "mane_core", "ears", "ears_in", "face",
         "forehead", "features", "whiskers"]

json.dump({"order": ORDER, "colors": COLORS, "stroke": STROKE, "paths": paths},
          open(f"{OUT}/lion4.json", "w"), indent=1)


def svg(scale, tx, ty, guide=False):
    s = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">',
         '<clipPath id="m"><circle cx="54" cy="54" r="36"/></clipPath>',
         '<g clip-path="url(#m)"><rect width="108" height="108" fill="#000"/>',
         f'<g transform="translate({tx},{ty}) scale({scale})">']
    for k in ORDER:
        st = STROKE.get(k)
        extra = f' stroke="#000" stroke-width="{st}" stroke-linejoin="round"' if st else ""
        s.append(f'<path d="{paths[k]}" fill="{COLORS[k]}"{extra}/>')
    s.append("</g></g>")
    if guide:
        s.append('<circle cx="54" cy="54" r="33" fill="none" stroke="#ff3b30" stroke-width="0.3"/>')
    s.append("</svg>")
    return "\n".join(s)


open(f"{OUT}/lion4.svg", "w").write(svg(0.70, 19, 17.6))
open(f"{OUT}/lion4_guide.svg", "w").write(svg(0.70, 19, 17.6, True))
print("ok")
