#!/usr/bin/env python3
"""Builds the store graphics that are not screenshots.

    python tools/build-store-assets.py

Writes docs/store/icon-512.png (the Play icon), docs/store/v<versionCode>/feature-graphic-v<versionCode>.png
(the listing header) and docs/store/og.jpg (the site's link preview). The keycap is read from
the launcher icon's vector drawables, so the store icon is the phone's icon at 512 pixels and
a change to the icon is one edit and a re-run. Screenshots are built by build-store-shots.py
once there are some.

Needs Chrome, which does the rendering, and fontTools for the one font the text uses.
"""
import base64
import os
import re
import subprocess
import sys
import tempfile
import urllib.request

from PIL import Image
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
WORK = os.path.join(HERE, 'work')
STORE = os.path.join(REPO, 'docs', 'store')
RES = os.path.join(REPO, 'app', 'src', 'main', 'res', 'drawable')

CHROME = next(
    (p for p in (
        r'C:\Program Files\Google\Chrome\Application\chrome.exe',
        r'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
        '/usr/bin/google-chrome',
        '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    ) if os.path.exists(p)),
    None,
)

FONT_URL = 'https://github.com/google/fonts/raw/main/ofl/newsreader/Newsreader%5Bopsz,wght%5D.ttf'
ITALIC_URL = ('https://github.com/google/fonts/raw/main/ofl/newsreader/'
              'Newsreader-Italic%5Bopsz,wght%5D.ttf')

# The palette is the icon's, which is where the app's colours come from too.
GROUND = '#0B0D14'
SURFACE = '#1A1E2A'
TEXT = '#EEF3FA'
TEXT_DIM = '#A7B0C2'
ACCENT = '#5FB0F0'
FACE_LIGHT = '#E6F4FF'
WALL_NEAR = (0x2E, 0x7B, 0xB8)
WALL_FAR = (0x17, 0x3C, 0x5E)

FEATURE = (1024, 500)
OG = (1200, 630)


def version_code():
    with open(os.path.join(REPO, 'app', 'build.gradle.kts'), encoding='utf-8') as f:
        return int(re.search(r'versionCode\s*=\s*(\d+)', f.read()).group(1))


def instanced(url, name, raw_name):
    """One static weight of a variable font, so no browser can pick a different one."""
    os.makedirs(WORK, exist_ok=True)
    cached = os.path.join(WORK, name)
    if not os.path.exists(cached):
        raw = os.path.join(WORK, raw_name)
        if not os.path.exists(raw):
            print('downloading', name)
            urllib.request.urlretrieve(url, raw)
        font = instancer.instantiateVariableFont(TTFont(raw), {'wght': 400, 'opsz': 36})
        font.save(cached)
    with open(cached, 'rb') as f:
        return base64.b64encode(f.read()).decode()


def fetch_fonts():
    return (
        instanced(FONT_URL, 'Newsreader.ttf', 'Newsreader-var.ttf'),
        instanced(ITALIC_URL, 'Newsreader-Italic.ttf', 'Newsreader-Italic-var.ttf'),
    )


# ------------------------------------------------------------------------- the icon
def read(name):
    with open(os.path.join(RES, name), encoding='utf-8') as f:
        return f.read()


def gradient_stops(block):
    return ''.join(
        f'<stop offset="{o}" stop-color="#{c[2:]}" stop-opacity="{int(c[:2], 16) / 255:.3f}"/>'
        for o, c in re.findall(r'android:offset="([\d.]+)" android:color="#([0-9A-Fa-f]{8})"', block)
    )


def icon_svg(crop):
    """The adaptive icon's two layers as one SVG, translated from the vector drawables.

    The drawables only use what this reads: rect and path fills, linear and radial
    gradients, translated groups, and one stroked path. Anything new in them has to be
    taught to this function or it is silently dropped.
    """
    bg = read('ic_launcher_background.xml')
    fg = read('ic_launcher_foreground.xml')
    defs = []
    body = []

    grads = re.findall(r'<gradient(.*?)</gradient>', bg, re.S)
    lin = re.search(r'startX="([\d.]+)" android:startY="([\d.]+)"\s+android:endX="([\d.]+)" android:endY="([\d.]+)"', grads[0])
    rad = re.search(r'centerX="([\d.]+)" android:centerY="([\d.]+)"\s+android:gradientRadius="([\d.]+)"', grads[1])
    defs.append(f'<linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="{lin[1]}" y1="{lin[2]}" '
                f'x2="{lin[3]}" y2="{lin[4]}">{gradient_stops(grads[0])}</linearGradient>')
    defs.append(f'<radialGradient id="glow" gradientUnits="userSpaceOnUse" cx="{rad[1]}" cy="{rad[2]}" '
                f'r="{rad[3]}">{gradient_stops(grads[1])}</radialGradient>')
    body.append('<rect width="108" height="108" fill="url(#bg)"/>')
    body.append('<rect width="108" height="108" fill="url(#glow)"/>')

    for m in re.finditer(r'<group android:translateX="([-\d.]+)" android:translateY="([-\d.]+)">\s*'
                         r'<path android:fillColor="(#[0-9A-Fa-f]+)"(?: android:fillAlpha="([\d.]+)")? '
                         r'android:pathData="([^"]+)" />', fg):
        alpha = f' opacity="{m[4]}"' if m[4] else ''
        body.append(f'<path d="{m[5]}" fill="{m[3]}"{alpha} transform="translate({m[1]},{m[2]})"/>')

    face = re.search(r'<path android:pathData="([^"]+)">\s*<aapt:attr name="android:fillColor">\s*<gradient(.*?)</gradient>', fg, re.S)
    lin = re.search(r'startX="([\d.]+)" android:startY="([\d.]+)"\s+android:endX="([\d.]+)" android:endY="([\d.]+)"', face[2])
    defs.append(f'<linearGradient id="face" gradientUnits="userSpaceOnUse" x1="{lin[1]}" y1="{lin[2]}" '
                f'x2="{lin[3]}" y2="{lin[4]}">{gradient_stops(face[2])}</linearGradient>')
    body.append(f'<path d="{face[1]}" fill="url(#face)"/>')

    caret = re.search(r'android:pathData="([^"]+)"\s+android:strokeColor="(#[0-9A-Fa-f]+)"\s+'
                      r'android:strokeWidth="([\d.]+)"', fg)
    body.append(f'<path d="{caret[1]}" fill="none" stroke="{caret[2]}" stroke-width="{caret[3]}" '
                'stroke-linecap="round" stroke-linejoin="round"/>')

    # The launcher shows the middle 72 of the 108 canvas; the store icon shows the same.
    inset = (108 - crop) / 2
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{inset} {inset} {crop} {crop}">'
            f'<defs>{"".join(defs)}</defs>{"".join(body)}</svg>')


# ------------------------------------------------------------------------ the wordmark
def wall_shadow(depth_em=0.06, slices=12):
    """The app's WordmarkView as a text-shadow: slices along the light vector, rim first."""
    parts = ['-0.012em -0.015em 0 rgba(255, 255, 255, 0.6)']
    for i in range(1, slices + 1):
        t = (i - 1) / (slices - 1)
        rgb = tuple(round(a + (b - a) * t) for a, b in zip(WALL_NEAR, WALL_FAR))
        step = depth_em * i / slices
        parts.append(f'{0.62 * step:.4f}em {0.78 * step:.4f}em 0 rgb({rgb[0]}, {rgb[1]}, {rgb[2]})')
    return ', '.join(parts)


def wordmark(size_px):
    return f'''<span class="word" style="font-size:{size_px}px"><span class="wall" aria-hidden="true">overkey</span><span class="face">overkey</span></span>'''


CSS = '''
@font-face {{
  font-family: Newsreader;
  src: url(data:font/ttf;base64,{font}) format('truetype');
  font-weight: 400; font-style: normal;
}}
@font-face {{
  font-family: Newsreader;
  src: url(data:font/ttf;base64,{italic}) format('truetype');
  font-weight: 400; font-style: italic;
}}
* {{ margin: 0; padding: 0; box-sizing: border-box; }}
body {{
  width: {w}px; height: {h}px; overflow: hidden;
  /* The icon's ground, lit from the same corner. */
  background:
    radial-gradient(circle at 42% 34%, {accent}55 0%, {accent}00 60%),
    linear-gradient(to bottom left, #1F2433 0%, {ground} 100%);
  font-family: Newsreader, Georgia, serif;
  color: {text};
  -webkit-font-smoothing: antialiased;
}}
.wrap {{ height: 100%; display: flex; align-items: center; }}
.mark {{ border-radius: 24%; overflow: hidden; flex: none; box-shadow: 18px 22px 50px -14px #000000cc; }}
.mark svg {{ display: block; width: 100%; height: 100%; }}
.word {{
  position: relative; display: inline-block;
  font-family: ui-sans-serif, "Segoe UI", Roboto, system-ui, sans-serif;
  font-weight: 700; letter-spacing: 0.06em; line-height: 1.1;
}}
.word .wall {{ position: absolute; left: 0; top: 0; color: rgb(46, 123, 184); text-shadow: {shadow}; }}
.word .face {{
  position: relative;
  background: linear-gradient(160deg, #FFFFFF 0%, {face} 45%, {accent} 100%);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}}
h1 {{ font-weight: 400; letter-spacing: -0.03em; line-height: 1.05; }}
h1 em {{ font-style: italic; color: {accent}; }}
p {{ color: {dim}; line-height: 1.38; }}
'''


def render(html, size, out_path):
    """Chrome, one page, one screenshot. Nothing else on the machine is touched."""
    if CHROME is None:
        sys.exit('Chrome not found; edit CHROME at the top of this script.')
    with tempfile.TemporaryDirectory() as tmp:
        page = os.path.join(tmp, 'page.html')
        with open(page, 'w', encoding='utf-8') as f:
            f.write(html)
        subprocess.run(
            [CHROME, '--headless', '--disable-gpu', '--hide-scrollbars',
             f'--window-size={size[0]},{size[1]}',
             f'--screenshot={out_path}', '--default-background-color=00000000',
             f'--user-data-dir={os.path.join(tmp, "profile")}',
             'file:///' + page.replace('\\', '/')],
            check=True, capture_output=True,
        )


def page(font, italic, size, body, extra_css=''):
    css = CSS.format(font=font, italic=italic, w=size[0], h=size[1], ground=GROUND, accent=ACCENT,
                     text=TEXT, dim=TEXT_DIM, face=FACE_LIGHT, shadow=wall_shadow())
    return f'<!doctype html><meta charset="utf-8"><style>{css}{extra_css}</style>{body}'


def build_icon(size=512):
    """The 512 square Play asks for. Full bleed; Play rounds the corners itself.

    Not versioned. Play holds one icon and a new upload replaces it, so the name is reused.
    """
    html = f'''<!doctype html><meta charset="utf-8"><style>
* {{ margin: 0; padding: 0; }}
body {{ width: {size}px; height: {size}px; overflow: hidden; }}
svg {{ display: block; width: {size}px; height: {size}px; }}
</style>{icon_svg(72)}'''
    out = os.path.join(STORE, 'icon-512.png')
    render(html, (size, size), out)
    return out


def build_feature(font, italic, version):
    body = f'''<div class="wrap" style="gap:56px;padding:0 76px">
  <div class="mark" style="width:300px;height:300px">{icon_svg(72)}</div>
  <div>
    <div style="margin-bottom:22px">{wordmark(64)}</div>
    <h1 style="font-size:58px">Modifier keys, without<br><em>replacing your keyboard.</em></h1>
    <p style="font-size:29px;margin-top:20px;max-width:560px">Ctrl, Alt, Esc, Tab and arrows in a bar
      that appears with the keyboard you already use.</p>
  </div>
</div>'''
    out_dir = os.path.join(STORE, f'v{version}')
    os.makedirs(out_dir, exist_ok=True)
    # Uploaded like a screenshot, so it is versioned like one.
    out = os.path.join(out_dir, f'feature-graphic-v{version}.png')
    render(page(font, italic, FEATURE, body), FEATURE, out)
    return out


def build_og(font, italic):
    """The 1200 by 630 link preview for the site page, as a JPEG: the PNG is half a megabyte."""
    body = f'''<div class="wrap" style="gap:64px;padding:0 90px">
  <div class="mark" style="width:340px;height:340px">{icon_svg(72)}</div>
  <div>
    <div style="margin-bottom:26px">{wordmark(72)}</div>
    <h1 style="font-size:62px">Modifier keys, without<br><em>replacing your keyboard.</em></h1>
    <p style="font-size:31px;margin-top:22px;max-width:620px">Ctrl, Alt, Esc, Tab and arrows in a bar
      that appears with the keyboard you already use.</p>
  </div>
</div>'''
    png = os.path.join(STORE, 'og.png')
    render(page(font, italic, OG, body), OG, png)
    out = os.path.join(STORE, 'og.jpg')
    Image.open(png).convert('RGB').save(out, quality=88, optimize=True, progressive=True)
    os.remove(png)
    return out


def main():
    os.makedirs(STORE, exist_ok=True)
    version = version_code()
    print(f'building for versionCode {version}')
    font, italic = fetch_fonts()
    print('wrote', build_icon())
    print('wrote', build_feature(font, italic, version))
    print('wrote', build_og(font, italic))


if __name__ == '__main__':
    main()
