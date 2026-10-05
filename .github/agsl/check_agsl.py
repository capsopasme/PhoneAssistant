"""Compile TimeStopView's AGSL with Skia's SkSL compiler and render sample frames."""
import math, re, sys, textwrap, traceback


def note(msg, level='notice'):
    # the run log isn't readable from where this is checked: annotations are
    print(f"::{level}::" + str(msg).replace('%', '%25').replace('\r', '').replace('\n', '%0A'), flush=True)


try:
    import numpy as np
    import skia
except Exception:
    note('IMPORT FAILED: ' + traceback.format_exc()[-1500:], 'error')
    sys.exit(1)

src = open('app/src/main/java/com/capsopasme/assistant/fx/TimeStopView.kt', encoding='utf-8').read()
m = re.search(r'private val AGSL = """(.*?)"""\.trimIndent\(\)', src, re.S)
if not m:
    note('AGSL not found in TimeStopView.kt', 'error'); sys.exit(1)
sksl = textwrap.dedent(m.group(1))
print(sksl)
print('skia', skia.__version__)

try:
    effect = skia.RuntimeEffect.MakeForShader(sksl)
except Exception as e:
    note(f'COMPILE FAILED (skia {skia.__version__}): {e}', 'error')
    sys.exit(1)
if effect is None:
    note('COMPILE FAILED: no effect', 'error'); sys.exit(1)
note(f'COMPILED OK with skia {skia.__version__}')
try:
    note('uniforms: ' + ', '.join(u.name for u in effect.uniforms()) + ' | children: ' + ', '.join(c.name for c in effect.children()))
except Exception as e:
    note(f'listing failed: {e}', 'warning')

W, H = 540, 1170
# a fake phone screen: coloured app bar, cards, text lines
img = np.zeros((H, W, 4), np.uint8)
img[..., 3] = 255
img[...] = (245, 245, 250, 255)
img[:60] = (30, 120, 200, 255)
img[60:170] = (40, 140, 225, 255)
rng = np.random.default_rng(1)
for i in range(7):
    y = 200 + i * 135
    img[y:y + 115, 24:W - 24] = (255, 255, 255, 255)
    c = rng.integers(40, 230, 3)
    img[y + 15:y + 100, 40:125, :3] = c
    for k in range(3):
        img[y + 22 + k * 26:y + 34 + k * 26, 145:W - 60 - k * 60, :3] = (60, 60, 70)
img[H - 30:H - 24, W // 2 - 60:W // 2 + 60] = (40, 40, 40, 255)
world = skia.Image.fromarray(img, colorType=skia.kRGBA_8888_ColorType)

def seg(t, a, b): return min(1.0, max(0.0, (t - a) / (b - a)))
def eo(x): return 1 - (1 - x) ** 3
def ei(x): return x ** 3

def frame(style, enter, t, R):
    u = dict(radius=0, zoom=1, inInv=0, inFrz=0, inClear=0, outInv=0, outFrz=0, outClear=0, ring=0, flash=0)
    if style == 'freeze' and enter:
        u['flash'] = 0.5 * (seg(t, 0, .05) - seg(t, .05, .28))
        u['zoom'] = 1 + .035 * math.sin(math.pi * seg(t, 0, .35))
        u['radius'] = R * eo(seg(t, .03, .55)); u['ring'] = 1 - seg(t, .45, .62)
        u['inInv'] = 1 - seg(t, .5, .85); u['inFrz'] = seg(t, .45, .9)
    elif style == 'freeze':
        u['radius'] = R * (1 - ei(seg(t, 0, .92))); u['ring'] = .8 * (1 - seg(t, .85, 1)); u['inFrz'] = 1
    elif enter:
        u['flash'] = .45 * (seg(t, 0, .04) - seg(t, .04, .22))
        u['zoom'] = 1 + .03 * math.sin(math.pi * seg(t, 0, .3))
        u['outInv'] = seg(t, 0, .05) * (1 - seg(t, .35, .7)); u['outFrz'] = seg(t, .35, .7)
        u['radius'] = R * eo(seg(t, .12, .85)); u['ring'] = 1 - seg(t, .78, .98); u['inClear'] = 1
    else:
        u['radius'] = R * (1 - ei(seg(t, 0, .82))); u['ring'] = 1 - seg(t, .8, .95); u['inClear'] = 1
        u['outFrz'] = 1 - seg(t, .25, .95)
    u['flash'] = min(1, max(0, u['flash']))
    return u

def render(style, enter, t, has_world=True):
    cx, cy = (W / 2, H * 0.5) if style == 'freeze' else (W / 2, H * 0.42)
    R = max(math.hypot(cx, cy), math.hypot(W - cx, cy), math.hypot(cx, H - cy), math.hypot(W - cx, H - cy)) * 1.04
    b = skia.RuntimeShaderBuilder(effect)
    vals = dict(hasWorld=1.0 if has_world else 0.0, size=[W, H], offset=[0, 0], worldScale=[1, 1], center=[cx, cy],
                edge=max(24, .06 * min(W, H)), fbNormal=0.0 if style == 'freeze' else 1.0,
                fbGraded=0.5 if style == 'freeze' else 1.0)
    vals.update(frame(style, enter, t, R))
    for k, v in vals.items():
        b.uniform(k).set(v if isinstance(v, list) else float(v))
    b.child('world').set(world.makeShader(skia.TileMode.kClamp, skia.TileMode.kClamp))
    shader = b.makeShader()
    surf = skia.Surface(W, H)
    c = surf.getCanvas()
    # what's under the overlay: the live app for the sheet, the call screen for the call
    if style == 'freeze':
        c.drawImage(world, 0, 0)
    else:
        c.clear(skia.Color(244, 245, 251))
        p = skia.Paint(Color=skia.Color(255, 185, 203), AntiAlias=True)
        c.drawCircle(cx, cy, 70, p)
    paint = skia.Paint(Shader=shader)
    c.drawRect(skia.Rect(0, 0, W, H), paint)
    return surf.makeImageSnapshot()

def main():
    rows = []
    for (style, enter, label) in [('freeze', True, 'sheet enter'), ('freeze', False, 'sheet exit'),
                                  ('reveal', True, 'call enter'), ('reveal', False, 'call exit')]:
        rows.append([render(style, enter, t) for t in (0.0, 0.04, 0.2, 0.35, 0.5, 0.7, 1.0)])
    rows.append([render('freeze', True, t, has_world=False) for t in (0.0, 0.04, 0.2, 0.35, 0.5, 0.7, 1.0)])
    s = 0.3
    cw, ch = int(W * s), int(H * s)
    sheet = skia.Surface(cw * 7 + 8 * 8, ch * len(rows) + 8 * (len(rows) + 1))
    sc = sheet.getCanvas(); sc.clear(skia.ColorBLACK)
    for r, row in enumerate(rows):
        for i, im in enumerate(row):
            sc.drawImageRect(im, skia.Rect.MakeXYWH(8 + i * (cw + 8), 8 + r * (ch + 8), cw, ch))
    sheet.makeImageSnapshot().save('agsl_frames.png', skia.kPNG)
    print('rendered agsl_frames.png')


try:
    main()
except Exception:
    note('RENDER FAILED: ' + traceback.format_exc()[-1500:], 'warning')
    note('RuntimeShaderBuilder API: ' + ', '.join(n for n in dir(skia.RuntimeShaderBuilder) if not n.startswith('_')), 'warning')
