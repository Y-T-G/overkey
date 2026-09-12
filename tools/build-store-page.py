"""Builds the store-asset review page, with every image inlined.

Everything the page says comes from the files it reviews: the listing text and the planned
screenshots from docs/store/listing.md, the graphics from build-store-assets.py. Changing what
the page says means changing one of those and running this again.

    python tools/build-store-page.py

It writes docs/store/review-page.html, which is the page published as the artifact.
"""
import base64
import html
import io
import os
import re

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
STORE = os.path.join(REPO, 'docs', 'store')
OUT = os.path.join(STORE, 'review-page.html')
LISTING = os.path.join(STORE, 'listing.md')


def version_code():
    with open(os.path.join(REPO, 'app', 'build.gradle.kts'), encoding='utf-8') as f:
        return int(re.search(r'versionCode\s*=\s*(\d+)', f.read()).group(1))


VERSION = version_code()
SHOT_DIR = os.path.join(STORE, f'v{VERSION}')


def section(title):
    """One section of listing.md by its heading, without the heading."""
    body = open(LISTING, encoding='utf-8').read()
    found = re.search(r'^## ' + re.escape(title) + r'\s*$(.*?)(?=^## |\Z)', body, re.S | re.M)
    return found.group(1).strip()


NAME = section('App name (30)').strip('`')
SHORT = section('Short description (80)').strip('`')
DESCRIPTION = section('Full description (4000)')

# The planned screenshots, from the table in listing.md: file, caption.
PLANNED = re.findall(r'^\| `([^`]+)` \| ([^|]+?) \|$', section('Screenshot captions'), re.M)


def uri(path, width=None, quality=None):
    im = Image.open(path).convert('RGB')
    if width:
        im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    buf = io.BytesIO()
    if quality:
        im.save(buf, format='JPEG', quality=quality, optimize=True, progressive=True)
        kind = 'jpeg'
    else:
        im.save(buf, format='PNG', optimize=True)
        kind = 'png'
    return f'data:image/{kind};base64,' + base64.b64encode(buf.getvalue()).decode()


feature = uri(os.path.join(SHOT_DIR, f'feature-graphic-v{VERSION}.png'), width=1024, quality=90)
icon = uri(os.path.join(STORE, 'icon-512.png'), width=256, quality=92)

# Real screenshots, if any have been built, else the planned set as empty frames.
shots = sorted(f for f in os.listdir(SHOT_DIR) if re.match(r'0\d-.*-v\d+\.png$', f)) if os.path.isdir(SHOT_DIR) else []
cards = []
if shots:
    for i, name in enumerate(shots, start=1):
        caption = next((c for f, c in PLANNED if f == name), '')
        src = uri(os.path.join(SHOT_DIR, name), width=520, quality=88)
        cards.append(f'''      <figure class="shot">
        <img src="{src}" alt="{html.escape(caption)}" width="520" height="924" loading="lazy">
        <figcaption>
          <span class="num">{i:02d}</span>
          <span class="cap">{html.escape(caption)}</span>
          <span class="file">{name}</span>
        </figcaption>
      </figure>''')
else:
    for i, (name, caption) in enumerate(PLANNED, start=1):
        cards.append(f'''      <figure class="shot">
        <div class="frame" aria-hidden="true"><span>not yet captured</span></div>
        <figcaption>
          <span class="num">{i:02d}</span>
          <span class="cap">{html.escape(caption)}</span>
          <span class="file">{name}</span>
        </figcaption>
      </figure>''')


# ------------------------------------------------------------------ the header word
# The app's WordmarkView as CSS: slices of side wall along the light vector (0.62, 0.78),
# darkest furthest away, a faint rim the other way, and a face that runs from white toward
# the accent. Offsets are in em, so the word carries its depth with it when it resizes.
WALL_NEAR, WALL_FAR = (0x2E, 0x7B, 0xB8), (0x17, 0x3C, 0x5E)
DEPTH_EM = 0.06
SLICES = 12


def wall_shadow():
    parts = ['-0.012em -0.015em 0 rgba(255, 255, 255, 0.55)']
    for i in range(1, SLICES + 1):
        t = (i - 1) / (SLICES - 1)
        rgb = tuple(round(a + (b - a) * t) for a, b in zip(WALL_NEAR, WALL_FAR))
        step = DEPTH_EM * i / SLICES
        parts.append(f'{0.62 * step:.4f}em {0.78 * step:.4f}em 0 rgb({rgb[0]}, {rgb[1]}, {rgb[2]})')
    return ',\n      '.join(parts)


# The bar's two rows, drawn as keycaps under the wordmark, so the header shows the thing
# the listing is selling.
BAR = [
    ['ESC', 'SHIFT', 'META', 'HOME', '↑', 'END', 'PGUP'],
    ['TAB', 'CTRL', 'ALT', '←', '↓', '→', 'PGDN'],
]
bar_rows = '\n'.join(
    '      <div class="krow">' + ''.join(f'<span class="key">{k}</span>' for k in row) + '</div>'
    for row in BAR
)

n_planned = len(PLANNED)
shots_meta = f'{len(shots)} at 1080 &times; 1920' if shots else f'none yet, {n_planned} planned'
shots_heading = 'In upload order' if shots else 'The planned set'
shots_intro = (
    'Play shows the first two or three before anyone scrolls, so the bar over a real '
    'keyboard and a chord from the grid come first.'
    if shots else
    f'No screenshots have been captured yet. Play needs at least two phone screenshots at '
    f'1080 &times; 1920 before the listing can be submitted. These {n_planned} are the plan, '
    f'in upload order; the first two are what the app is for and what no keyboard does on '
    f'its own.'
)

HTML = f'''<title>Overkey Store Assets</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Newsreader:ital,opsz,wght@0,6..72,300;0,6..72,400;1,6..72,300&family=IBM+Plex+Sans:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap">
<style>
  /* One committed ground, the icon's. Every colour is painted explicitly so the page holds
     on any host, in either theme. */
  :root {{
    --ink: #0B0D14;
    --panel: #141823;
    --panel-lift: #1A1E2A;
    --rule: #2B3242;
    --rule-soft: #1F2533;
    --text: #EEF3FA;
    --dim: #A7B0C2;
    --faint: #6E7789;
    --accent: #5FB0F0;
    --face-light: #E6F4FF;
  }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0;
    background: var(--ink);
    color: var(--text);
    font-family: "IBM Plex Sans", ui-sans-serif, system-ui, sans-serif;
    font-size: 16px;
    line-height: 1.6;
    -webkit-font-smoothing: antialiased;
  }}
  .wrap {{ max-width: 1180px; margin: 0 auto; padding: 0 clamp(1rem, 0.6rem + 1.4vw, 1.5rem); }}
  section {{ padding: clamp(3rem, 6vw, 5rem) 0; border-top: 1px solid var(--rule-soft); }}
  header {{ padding: clamp(3rem, 7vw, 5.5rem) 0 clamp(2rem, 4vw, 3rem); }}
  h1, h2, h3 {{ font-family: Newsreader, Georgia, serif; font-weight: 400; text-wrap: balance; }}
  h1 {{ font-size: clamp(2.6rem, 1.4rem + 4.4vw, 4.2rem); line-height: 1; letter-spacing: -0.025em; margin: 0 0 1rem; }}
  h1 em {{ font-style: italic; font-weight: 300; color: var(--accent); }}
  h2 {{ font-size: clamp(1.8rem, 1.2rem + 1.8vw, 2.5rem); line-height: 1.1; margin: 0 0 0.6rem; letter-spacing: -0.01em; }}
  h3 {{ font-size: 1.2rem; margin: 2rem 0 0.5rem; }}
  p {{ margin: 0 0 1rem; max-width: 66ch; }}
  .lede {{ font-size: 1.1rem; color: var(--dim); }}
  .eyebrow {{
    font-family: "IBM Plex Mono", ui-monospace, monospace;
    font-size: 0.7rem; letter-spacing: 0.16em; text-transform: uppercase;
    color: var(--accent); margin: 0 0 1rem;
  }}
  .meta {{
    display: flex; flex-wrap: wrap; gap: 0.4rem 1.6rem; margin-top: 1.4rem;
    font-family: "IBM Plex Mono", ui-monospace, monospace;
    font-size: 0.76rem; color: var(--faint); letter-spacing: 0.04em;
  }}
  .meta b {{ color: var(--dim); font-weight: 500; }}

  .feature {{ margin-top: 2rem; border-radius: 14px; overflow: hidden; border: 1px solid var(--rule-soft); }}
  .feature img {{ display: block; width: 100%; height: auto; }}

  .gallery {{
    display: grid; gap: 26px;
    grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
    margin-top: 2rem;
  }}
  .shot {{ margin: 0; }}
  .shot img, .shot .frame {{
    display: block; width: 100%; height: auto; border-radius: 12px;
    border: 1px solid var(--rule-soft);
  }}
  .shot .frame {{
    aspect-ratio: 1080 / 1920; background: var(--panel);
    border-style: dashed; border-color: var(--rule);
    display: grid; place-items: center;
    font-family: "IBM Plex Mono", ui-monospace, monospace;
    font-size: 0.72rem; letter-spacing: 0.1em; text-transform: uppercase; color: var(--faint);
  }}
  .shot figcaption {{ display: grid; gap: 0.2rem; margin-top: 0.8rem; }}
  .num {{
    font-family: "IBM Plex Mono", ui-monospace, monospace;
    font-size: 0.7rem; color: var(--accent); letter-spacing: 0.1em;
  }}
  .cap {{ font-family: Newsreader, Georgia, serif; font-size: 1.15rem; line-height: 1.2; }}
  .file {{
    font-family: "IBM Plex Mono", ui-monospace, monospace;
    font-size: 0.72rem; color: var(--faint);
  }}

  .split {{ display: grid; gap: clamp(1.5rem, 4vw, 2.5rem); align-items: start; }}
  @media (min-width: 820px) {{ .split {{ grid-template-columns: 256px minmax(0, 1fr); }} }}

  .icon {{ border-radius: 22%; overflow: hidden; border: 1px solid var(--rule-soft); }}
  .icon img {{ display: block; width: 100%; height: auto; }}

  .copy {{
    background: var(--panel); border: 1px solid var(--rule-soft); border-radius: 12px;
    padding: clamp(1rem, 3vw, 1.6rem); margin-top: 1.4rem;
  }}
  .copy .label {{
    display: flex; justify-content: space-between; align-items: baseline; gap: 1rem;
    font-family: "IBM Plex Mono", ui-monospace, monospace;
    font-size: 0.72rem; letter-spacing: 0.1em; text-transform: uppercase;
    color: var(--faint); margin-bottom: 0.6rem;
  }}
  .copy .count {{ font-variant-numeric: tabular-nums; }}
  .copy pre {{
    margin: 0; white-space: pre-wrap; font-family: inherit; font-size: 0.95rem;
    color: var(--text); line-height: 1.62;
  }}
  .copy .one {{ font-size: 1.05rem; color: var(--face-light); }}
  button.copybtn {{
    font: inherit; font-size: 0.78rem; color: var(--accent);
    background: transparent; border: 1px solid var(--rule); border-radius: 999px;
    padding: 0.18rem 0.75rem; cursor: pointer;
  }}
  button.copybtn:hover {{ border-color: var(--accent); }}
  button.copybtn:focus-visible, a:focus-visible {{ outline: 2px solid var(--accent); outline-offset: 3px; }}

  .scroll {{ overflow-x: auto; }}
  table {{ border-collapse: collapse; width: 100%; margin-top: 1.4rem; font-size: 0.92rem; }}
  th, td {{ text-align: left; padding: 0.6rem 1rem 0.6rem 0; border-bottom: 1px solid var(--rule-soft); vertical-align: top; }}
  th {{
    font-family: "IBM Plex Mono", ui-monospace, monospace;
    font-size: 0.7rem; letter-spacing: 0.1em; text-transform: uppercase; color: var(--faint);
    font-weight: 500;
  }}
  code {{
    font-family: "IBM Plex Mono", ui-monospace, monospace; font-size: 0.86em;
    background: var(--panel-lift); color: var(--face-light);
    padding: 0.12em 0.4em; border-radius: 4px;
  }}
  ul {{ max-width: 66ch; padding-left: 1.1rem; }}
  li {{ margin-bottom: 0.35rem; }}
  footer {{ padding: 3rem 0 4rem; border-top: 1px solid var(--rule-soft); color: var(--faint); font-size: 0.88rem; }}
  a {{ color: var(--accent); text-underline-offset: 3px; }}

  /* The header: the wordmark drawn twice, a wall copy carrying the shadow slices and a
     face copy on top holding the gradient, and the bar's fourteen keys under it. */
  .brand {{
    display: flex; flex-wrap: wrap; gap: clamp(1.6rem, 0.6rem + 3vw, 3.4rem);
    align-items: flex-end; padding-bottom: clamp(1.6rem, 3vw, 2.4rem);
    border-bottom: 1px solid var(--rule-soft); margin-bottom: clamp(1.8rem, 4vw, 3rem);
  }}
  .word {{
    position: relative; display: inline-block;
    font-family: "IBM Plex Sans", ui-sans-serif, system-ui, sans-serif;
    font-weight: 700; font-size: clamp(2.6rem, 1.6rem + 3vw, 4.4rem);
    letter-spacing: 0.06em; line-height: 1.2; padding-right: 0.08em;
  }}
  .word .wall {{
    position: absolute; left: 0; top: 0; color: #2E7BB8;
    text-shadow:
      {wall_shadow()};
  }}
  .word .face {{
    position: relative;
    background: linear-gradient(160deg, #FFFFFF 0%, var(--face-light) 45%, var(--accent) 100%);
    -webkit-background-clip: text; background-clip: text; color: transparent;
  }}
  .bar {{ display: grid; gap: 4px; }}
  .krow {{ display: grid; grid-template-columns: repeat(7, 1fr); gap: 4px; }}
  .key {{
    font-family: "IBM Plex Mono", ui-monospace, monospace; font-size: 0.66rem; font-weight: 500;
    letter-spacing: 0.04em; color: var(--dim); text-align: center;
    background: var(--panel-lift); border: 1px solid var(--rule-soft); border-radius: 5px;
    padding: 0.28rem 0.5rem; min-width: 3.2em;
    box-shadow: 1px 1.5px 0 #173C5E;
  }}
  .krow .key:nth-child(2), .krow .key:nth-child(3) {{ color: var(--accent); }}
  @media (prefers-reduced-motion: no-preference) {{
    .word {{ transition: transform 240ms ease; }}
    .brand:hover .word {{ transform: translate(-0.012em, -0.015em); }}
  }}
</style>

<div class="wrap">
  <header>
    <div class="brand">
      <span class="word"><span class="wall" aria-hidden="true">overkey</span><span class="face">overkey</span></span>
      <div class="bar" aria-label="The key bar">
{bar_rows}
      </div>
    </div>
    <p class="eyebrow">Overkey &middot; play store assets</p>
    <h1>Six screenshots, <em>one graphic.</em></h1>
    <p class="lede">The listing for versionCode {VERSION}. The screenshots are captured on the
      phone by a script and composed on the icon's ground; the icon and the feature graphic
      are drawn from the launcher icon's own vector drawables. A caption change is an edit
      and a re-run.</p>
    <div class="meta">
      <span><b>Screenshots</b> {shots_meta}</span>
      <span><b>Feature</b> 1024 &times; 500</span>
      <span><b>Icon</b> 512</span>
      <span><b>Build</b> tools/build-store-assets.py</span>
    </div>
  </header>

  <section>
    <p class="eyebrow">feature graphic</p>
    <h2>The listing header</h2>
    <p>The icon and the one line that says what the app is for: modifier keys without
      replacing the keyboard. Play crops this on some surfaces, so nothing important sits
      near an edge.</p>
    <div class="feature"><img src="{feature}" alt="Feature graphic" width="1024" height="500"></div>
  </section>

  <section>
    <p class="eyebrow">screenshots</p>
    <h2>{shots_heading}</h2>
    <p>{shots_intro}</p>
    <div class="gallery">
{chr(10).join(cards)}
    </div>
  </section>

  <section>
    <p class="eyebrow">icon</p>
    <div class="split">
      <div class="icon"><img src="{icon}" alt="App icon" width="256" height="256"></div>
      <div>
        <h2>512 square</h2>
        <p>Full bleed, no rounded corners: Play masks it itself, and a pre-rounded icon comes
          out with a double border. This is the middle 72 dp of the adaptive icon, which is
          what a launcher shows, rendered at store size: the same keycap, the same seven
          slices of side wall, the same caret.</p>
        <p style="font-family:'IBM Plex Mono',monospace;font-size:0.86em;color:var(--faint)">docs/store/icon-512.png</p>
      </div>
    </div>
  </section>

  <section>
    <p class="eyebrow">listing text</p>
    <h2>What goes in the fields</h2>
    <p>Character limits are Google's. The name uses all of its thirty.</p>

    <div class="copy">
      <div class="label"><span>App name &middot; limit 30</span>
        <span><span class="count">{len(NAME)} characters</span> &nbsp;<button class="copybtn" data-copy="name">Copy</button></span></div>
      <pre class="one" id="name">{html.escape(NAME)}</pre>
    </div>

    <div class="copy">
      <div class="label"><span>Short description &middot; limit 80</span>
        <span><span class="count">{len(SHORT)} characters</span> &nbsp;<button class="copybtn" data-copy="short">Copy</button></span></div>
      <pre class="one" id="short">{html.escape(SHORT)}</pre>
    </div>

    <div class="copy">
      <div class="label"><span>Full description &middot; limit 4000</span>
        <span><span class="count">{len(DESCRIPTION)} characters</span> &nbsp;<button class="copybtn" data-copy="full">Copy</button></span></div>
      <pre id="full">{html.escape(DESCRIPTION)}</pre>
    </div>
  </section>

  <section>
    <p class="eyebrow">data safety</p>
    <h2>Every row is No</h2>
    <p>Nothing is collected and nothing is shared. The only socket the app opens is to
      <code>127.0.0.1:27301</code>, the helper on the same phone, from
      <code>InjectorClient.kt</code>; the helper in <code>Injector.kt</code> binds the same
      address. That is why <code>INTERNET</code> is declared, and it is the one permission a
      reviewer may ask about.</p>
    <div class="scroll">
      <table>
        <tr><th>Permission</th><th>What it is for</th></tr>
        <tr><td><code>SYSTEM_ALERT_WINDOW</code></td><td>The bar, the chord grid and aim mode are overlay windows.</td></tr>
        <tr><td><code>FOREGROUND_SERVICE</code>, <code>FOREGROUND_SERVICE_SPECIAL_USE</code></td>
          <td>The service that polls the keyboard's insets and owns the overlay. The Console asks for a reason for the special-use type; that sentence is it.</td></tr>
        <tr><td><code>POST_NOTIFICATIONS</code></td><td>The one notification, the only way to pin the bar without a keyboard.</td></tr>
        <tr><td><code>RECEIVE_BOOT_COMPLETED</code></td><td>Restarts the service after a reboot or an update.</td></tr>
        <tr><td><code>INTERNET</code></td><td>Loopback only, as above.</td></tr>
      </table>
    </div>
    <p style="margin-top:1.4rem">No accessibility service, no analytics SDK, no advertising
      id, no crash reporting. The only third-party code is the Shizuku client library, which
      talks to the Shizuku app on the same phone. The helper injects input events, which is
      what Shizuku and adb are for, and the listing says so in plain words. Root is one of
      three ways to start it; the app works fully without it.</p>
  </section>

  <footer>
    <p>Built by <code>tools/build-store-page.py</code> from <code>docs/store/listing.md</code>.
      The site page is <a href="https://noblebits.dev/overkey/">noblebits.dev/overkey</a>.</p>
  </footer>
</div>
<script>
  document.querySelectorAll("button.copybtn").forEach(function (b) {{
    b.addEventListener("click", function () {{
      var text = document.getElementById(b.dataset.copy).textContent;
      navigator.clipboard.writeText(text).then(function () {{
        b.textContent = "Copied";
        setTimeout(function () {{ b.textContent = "Copy"; }}, 1400);
      }});
    }});
  }});
</script>
'''

with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
    f.write(HTML)
print('wrote', OUT, f'{os.path.getsize(OUT) // 1024} KB')
