"""Серьёзный геометрический лев анфас: грани, симметрия, тёмные швы. Грива — ровные «языки пламени»."""
import math

C = (50, 51)
F = {
    'top': (50, 26), 'fh': (60, 27.5), 'temple': (70, 34.5), 'cheek': (76, 48), 'cheek2': (73.5, 60.5),
    'jaw': (66.5, 71), 'chin_s': (58, 78.5), 'chin': (50, 80.5),
    'brow_in': (51.6, 40.2), 'brow_out': (68, 37),
    'eye_in': (54.6, 45.2), 'eye_top': (60, 42.4), 'eye_out': (66, 41.4), 'eye_low': (61, 45.4),
    'bridge': (53, 44.5), 'nose_top': (55.8, 55.6), 'nose_side': (60, 59.2), 'nose_bot': (53.4, 63.8), 'nose_c': (50, 64.8),
    'muz': (61.5, 61.6), 'muz_out': (65, 66.4), 'muz_low': (60.5, 72), 'mouth': (53.8, 72.4), 'mouth_c': (50, 70.8),
}
P = lambda *k: [F[x] if isinstance(x, str) else x for x in k]
mirror = lambda pts: [(100 - x, y) for x, y in pts]

FACE = [
    P('top', 'fh', 'brow_out', 'brow_in', (50, 40.4)),
    P('fh', 'temple', 'brow_out'),
    P('brow_in', 'brow_out', 'eye_out', 'eye_top', 'eye_in', 'bridge'),
    P('brow_out', 'temple', 'cheek', 'eye_out'),
    P('eye_out', 'cheek', 'cheek2', 'muz_out', 'muz', 'nose_side', 'eye_low'),
    P('bridge', 'eye_in', 'eye_low', 'nose_side', 'nose_top'),
    P((50, 40.4), 'brow_in', 'bridge', 'nose_top', (50, 55.4)),
    P('cheek2', 'jaw', 'muz_low', 'muz_out'),
    P('nose_side', 'muz', 'muz_out', 'muz_low', 'mouth', 'nose_bot'),
    P('jaw', 'chin_s', 'mouth', 'muz_low'),
    P('mouth', 'chin_s', 'chin', 'mouth_c'),
    P('nose_bot', 'mouth', 'mouth_c', 'nose_c'),
]
NOSE = P((50, 55.4), 'nose_top', 'nose_side', 'nose_bot', 'nose_c')
EYE = P('eye_in', 'eye_top', 'eye_out', 'eye_low')

ring_keys = ['top', 'fh', 'temple', 'cheek', 'cheek2', 'jaw', 'chin']
inner = []
for i, k in enumerate(ring_keys):
    inner.append(F[k])
    if i < len(ring_keys) - 1:
        a, b = F[k], F[ring_keys[i + 1]]
        inner.append(((a[0] + b[0]) / 2, (a[1] + b[1]) / 2))

def rot(v, deg):
    r = math.radians(deg)
    return (v[0] * math.cos(r) - v[1] * math.sin(r), v[0] * math.sin(r) + v[1] * math.cos(r))

def outer_pts(L_tip, L_val, sweep):
    out = []
    n = len(inner)
    for j, p in enumerate(inner):
        t = j / (n - 1)                       # 0 — макушка, 1 — подбородок
        v = (p[0] - C[0], p[1] - C[1])
        d = math.hypot(*v)
        v = (v[0] / d, v[1] / d)
        if 0 < j < n - 1:
            v = rot(v, sweep(t))
        L = L_tip(t) if j % 2 == 0 else L_val(t)
        out.append((p[0] + v[0] * L, p[1] + v[1] * L))
    return out

def mane_facets(outer):
    fs = []
    for j in range(len(inner) - 1):
        fs.append([inner[j], outer[j], outer[j + 1]])
        fs.append([inner[j], outer[j + 1], inner[j + 1]])
    return fs

L_tip = lambda t: 11 + 6 * math.sin(math.pi * t) + 6 * t ** 2
L_val = lambda t: 6 + 4 * math.sin(math.pi * t) + 2 * t
sweep = lambda t: 12 + 10 * t
OUT1 = outer_pts(L_tip, L_val, sweep)
MANE = mane_facets(OUT1)
# второй, задний слой гривы: лучи между лучами первого слоя
L2_tip = lambda t: 17 + 8 * math.sin(math.pi * t) + 5 * t ** 2
L2_val = lambda t: 7 + 4 * math.sin(math.pi * t)
OUT2 = []
n = len(inner)
for j, p in enumerate(inner):
    t = j / (n - 1)
    v = (p[0] - C[0], p[1] - C[1]); d = math.hypot(*v); v = (v[0] / d, v[1] / d)
    if 0 < j < n - 1:
        v = rot(v, sweep(t) + 6)
    L = L2_tip(t) if j % 2 == 1 else L2_val(t)
    OUT2.append((p[0] + v[0] * L, p[1] + v[1] * L))
BACK = []
for j in range(n - 1):
    BACK.append([inner[j], OUT2[j], OUT2[j + 1]])
    BACK.append([inner[j], OUT2[j + 1], inner[j + 1]])

def shade(pts, base, amp, light=(-0.5, -0.86)):
    x = sum(p[0] for p in pts) / len(pts) - C[0]
    y = sum(p[1] for p in pts) / len(pts) - C[1]
    n = math.hypot(x, y) or 1
    return max(0.0, min(1.0, base + amp * ((x / n) * light[0] + (y / n) * light[1])))

def tone(pal, v):
    stops = pal
    v = max(0, min(1, v)) * (len(stops) - 1)
    i = min(int(v), len(stops) - 2)
    f = v - i
    a, b = stops[i], stops[i + 1]
    return '#%02x%02x%02x' % tuple(round(a[k] + (b[k] - a[k]) * f) for k in range(3))

GOLD = [(96, 64, 18), (176, 128, 52), (226, 184, 104), (252, 228, 164)]
WHITE = [(92, 92, 96), (168, 168, 172), (222, 222, 226), (255, 255, 255)]

def path(pts):
    return 'M' + ' L'.join('%.2f,%.2f' % q for q in pts) + ' Z'

def svg(pal, size=512, gap=0.85, scale=0.82, bg='#000000'):
    body = []
    for i, f in enumerate(BACK):
        for q in (f, mirror(f)):
            body.append((q, shade(q, 0.34 + (0.05 if i % 2 else -0.03), 0.3)))
    for i, f in enumerate(MANE):
        j = 0.06 if i % 2 == 0 else -0.06
        for q in (f, mirror(f)):
            body.append((q, shade(q, 0.58 + j, 0.36)))
    for i, f in enumerate(FACE):
        for q in (f, mirror(f)):
            body.append((q, shade(q, 0.72 + (0.04 if i % 2 else 0), 0.26)))
    s = []
    for q, v in body:
        s.append(f'<path d="{path(q)}" fill="{tone(pal, v)}" stroke="{bg}" stroke-width="{gap}" stroke-linejoin="round"/>')
    nose = NOSE + mirror(NOSE)[1:-1][::-1]
    s.append(f'<path d="{path(nose)}" fill="{tone(pal, 0.16)}" stroke="{bg}" stroke-width="{gap}" stroke-linejoin="round"/>')
    for e in (EYE, mirror(EYE)):
        s.append(f'<path d="{path(e)}" fill="{bg}"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="{size}" height="{size}">'
            f'<rect width="100" height="100" fill="{bg}"/>'
            f'<g transform="translate(50 50) scale({scale}) translate(-50 -55)">{"".join(s)}</g></svg>')

def facets(pal):
    """Грани льва для Android: [(pathData, '#RRGGBB')], порядок отрисовки сохранён."""
    out = []
    for i, f in enumerate(BACK):
        for q in (f, mirror(f)):
            out.append((path(q), tone(pal, shade(q, 0.34 + (0.05 if i % 2 else -0.03), 0.3))))
    for i, f in enumerate(MANE):
        j = 0.06 if i % 2 == 0 else -0.06
        for q in (f, mirror(f)):
            out.append((path(q), tone(pal, shade(q, 0.58 + j, 0.36))))
    for i, f in enumerate(FACE):
        for q in (f, mirror(f)):
            out.append((path(q), tone(pal, shade(q, 0.72 + (0.04 if i % 2 else 0), 0.26))))
    nose = NOSE + mirror(NOSE)[1:-1][::-1]
    out.append((path(nose), tone(pal, 0.16)))
    for e in (EYE, mirror(EYE)):
        out.append((path(e), '#000000'))
    return out


if __name__ == '__main__':
    open('lion8_gold.svg', 'w').write(svg(GOLD))
    open('lion8_white.svg', 'w').write(svg(WHITE))
