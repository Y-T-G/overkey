#!/usr/bin/env python3
"""Composes the Play screenshots from the raw captures.

    python tools/capture-store-shots.py   # drives the phone, writes docs/store/raw/v<code>/
    python tools/build-store-shots.py     # this: writes docs/store/v<code>/0N-<name>-v<code>.png

Each shot is a caption over a phone on the icon's ground, at 1080 x 1920. The phone is
shown larger than would fit and whatever does not fit is cut off at the top, because
everything this app does happens in the bottom third of the screen and a whole phone at
fitting size makes the bar unreadable. The settings shot is cut at the bottom instead.

Fonts and the ground come from build-store-assets.py, so the set matches the feature
graphic. Needs Chrome, fontTools and Pillow.
"""
import base64
import importlib.util
import io
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))

spec = importlib.util.spec_from_file_location('assets', os.path.join(HERE, 'build-store-assets.py'))
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)

VERSION = assets.version_code()
STORE = assets.STORE
RAW = os.path.join(STORE, 'raw', f'v{VERSION}')
OUT = os.path.join(STORE, f'v{VERSION}')

WIDTH, HEIGHT = 1080, 1920

# The status bar is cut off every capture: a real clock and a real battery date the image
# and sell nothing.
STATUS_BAR = 104

# Each caption names the feature in the shot. The order is the order Play shows them in,
# so what the app is for and what no keyboard does on its own come first.
SHOTS = [
    ('01-notes.png', 'Modifier keys over your own keyboard', 'bottom'),
    ('02-chord.png', 'Ctrl+C from a grid over the keyboard', 'bottom'),
    ('03-browser.png', 'Works in any app', 'bottom'),
    ('04-pinned.png', 'Ctrl+Z with no keyboard open', 'bottom'),
    ('05-settings.png', 'Root, Shizuku or one adb command', 'top'),
    ('06-themes.png', 'Twelve themes, or your own accent', 'bottom'),
]

SHOT_CSS = '''
.wrap {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  /* 8% of the short edge is the floor for every margin in the system. */
  padding: 88px 86px 88px;
}
.caption {
  font-size: 54px;
  line-height: 1.14;
  letter-spacing: -0.015em;
  text-align: center;
  text-wrap: balance;
  max-width: 940px;
  flex: none;
  color: #EEF3FA;
}
/* The device takes whatever the caption leaves, so a two line caption shrinks the
   screen rather than pushing it off the canvas, and every shot still lines up. */
.device {
  flex: 1 1 auto;
  min-height: 0;
  margin-top: 48px;
  width: 820px;
  display: flex;
  overflow: hidden;
  /* Light from the upper left, so the shadow falls along (0.62, 0.78). */
  box-shadow: 28px 35px 90px -20px #000000BB, 0 0 0 1px #E6F4FF26;
}
/* Whatever does not fit is cut off at the end that matters less. */
.device.bottom { border-radius: 0 0 40px 40px; align-items: flex-end; }
.device.top { border-radius: 40px 40px 0 0; align-items: flex-start; margin-bottom: -88px; }
.device img { display: block; width: 100%; height: auto; flex: none; }
'''


def screen_data_uri(name):
    """A capture with its status bar removed, as a data URI."""
    im = Image.open(os.path.join(RAW, name)).convert('RGB')
    im = im.crop((0, STATUS_BAR, im.width, im.height))
    buf = io.BytesIO()
    im.save(buf, format='PNG', optimize=True)
    return 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()


def build_shot(font, italic, name, caption, anchor, index):
    body = f'''<div class="wrap">
  <div class="caption">{caption}</div>
  <div class="device {anchor}"><img src="{screen_data_uri(name)}"></div>
</div>'''
    # The ordinal prefix fixes the order Play shows the shots in. The version code suffix
    # keeps the name off every name a previous release already used.
    stem = os.path.splitext(name.split('-', 1)[1])[0]
    out = os.path.join(OUT, f'{index:02d}-{stem}-v{VERSION}.png')
    assets.render(assets.page(font, italic, (WIDTH, HEIGHT), body, SHOT_CSS), (WIDTH, HEIGHT), out)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f'building for versionCode {VERSION}')
    font, italic = assets.fetch_fonts()
    for i, (name, caption, anchor) in enumerate(SHOTS, start=1):
        print('wrote', build_shot(font, italic, name, caption, anchor, i))


if __name__ == '__main__':
    main()
