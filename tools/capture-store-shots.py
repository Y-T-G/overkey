#!/usr/bin/env python3
"""Drives the phone through the store screenshots and pulls the raw captures.

    python tools/capture-store-shots.py [device]

Writes docs/store/raw/v<versionCode>/*.png at the phone's own size; build-store-shots.py
composes them. Needs a rooted phone on adb (the prefs are edited with su so each shot can
choose its theme), Google Keep, a browser, and the injector running so the bar's keys
work. Every step here is an `input tap` at coordinates read from the bar's window frame, so it survives a different keyboard height but not a different screen size.
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
# The adb serial, from the command line or ANDROID_SERIAL; adb's own default otherwise.
DEVICE = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('ANDROID_SERIAL')
PKG = 'dev.noblebits.overkey'
# Indexes into Prefs.THEMES. The phone's own accent is 0 and the default, but it is a
# picture of that day's wallpaper, so the shots use fixed themes.
TERMUX = 1
DRACULA = 2


def version_code():
    with open(os.path.join(REPO, 'app', 'build.gradle.kts'), encoding='utf-8') as f:
        return int(re.search(r'versionCode\s*=\s*(\d+)', f.read()).group(1))


RAW = os.path.join(REPO, 'docs', 'store', 'raw', f'v{version_code()}')


def adb(*args, **kw):
    target = ['-s', DEVICE] if DEVICE else []
    return subprocess.run(['adb'] + target + list(args), check=True, capture_output=True, **kw)


def sh(cmd):
    return adb('shell', cmd).stdout.decode(errors='replace')


def tap(x, y):
    sh(f'input tap {x} {y}')
    time.sleep(0.6)


def swipe_hold(x, y, ms):
    """A long press: a swipe that does not move."""
    sh(f'input swipe {x} {y} {x} {y} {ms}')
    time.sleep(0.4)


def type_text(s):
    sh('input text ' + s.replace(' ', '%s'))


def enter():
    sh('input keyevent 66')


def shot(name):
    os.makedirs(RAW, exist_ok=True)
    out = os.path.join(RAW, name)
    time.sleep(0.8)
    with open(out, 'wb') as f:
        f.write(adb('exec-out', 'screencap', '-p').stdout)
    print('wrote', out)


def bar_frame():
    """The bar window's [left, top, right, bottom], or None while it is not shown."""
    dump = sh('dumpsys window windows')
    m = re.search(r'Window\{\w+ u0 ' + re.escape(PKG) + r'\}:.*?mFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', dump, re.S)
    return [int(v) for v in m.groups()] if m else None


def bar_key(col, row):
    """Centre of a bar key, seven columns by two rows, the handle strip below them."""
    f = bar_frame()
    assert f, 'the bar is not on screen'
    w = f[2] - f[0]
    rows = (f[3] - f[1]) * 171 / 201  # two rows of the 201px total, the rest is the handle
    return f[0] + int(w * (col + 0.5) / 7), f[1] + int(rows * (row + 0.5) / 2)


XML = f'/data/data/{PKG}/shared_prefs/{PKG.split(".")[-1]}.xml'
WORK = os.path.join(HERE, 'work')


def read_prefs():
    """The app's preferences file, as text."""
    return sh(f'su -c "cat {XML}"')


def write_prefs(text):
    """Replaces the preferences file and restarts the service, which reads them on start.

    Written by the app's own uid with its SELinux label: a file left by root is unreadable
    to the app, which then starts from empty preferences and throws every setting away.
    The service is started directly rather than through the settings screen, so whatever
    is in front stays in front.
    """
    os.makedirs(WORK, exist_ok=True)
    local = os.path.join(WORK, 'prefs.xml')
    with open(local, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    sh(f'am force-stop {PKG}')
    adb('push', local, '/data/local/tmp/prefs.xml')
    owner = sh(f'su -c "stat -c %U /data/data/{PKG}"').strip()
    sh(f'su -c "cp /data/local/tmp/prefs.xml {XML} && chown {owner}:{owner} {XML} && chmod 660 {XML} && restorecon {XML}"')
    time.sleep(1)
    sh(f'su -c "am start-foreground-service -n {PKG}/.OverlayService"')
    time.sleep(3)


def with_theme(prefs, index):
    """The preferences with the theme set to [index] and the grid at its default opacity;
    the bar position and calibration stay the phone's own."""
    prefs = re.sub(r'\s*<int name="(alpha|fg_alpha)" value="\d+" />', '', prefs)
    if '"theme"' in prefs:
        return re.sub(r'name="theme" value="\d+"', f'name="theme" value="{index}"', prefs)
    return prefs.replace('<map>', f'<map>\n    <int name="theme" value="{index}" />')


def new_note():
    """One new note in Google Keep, typed with input text. It is a real note in the
    account on the phone: delete it afterwards. Only one is made per run.

    Keep is stopped first. With an editor already open, keep://note/new lands in that note
    and the typing goes into it, which is how two real notes got damaged the first time.
    The screen is checked for an empty note before a key is sent, and the run stops if it
    is not one.
    """
    sh('am force-stop com.google.android.keep')
    time.sleep(1)
    sh('am start -n com.google.android.keep/.activities.ShortcutResolverActivity -a android.intent.action.VIEW -d keep://note/new')
    time.sleep(4)
    if not empty_note():
        sys.exit('Keep did not open an empty note; nothing typed.')
    tap(200, 350)   # Title
    type_text('Shopping')
    tap(200, 460)   # Note
    type_text('oat milk'); enter()
    type_text('coffee beans'); enter()
    type_text('rice'); enter(); enter()
    type_text('Call the dentist on Monday')
    time.sleep(1.5)


def empty_note():
    """True if the editor holds no text. Typed text is white; the Title and Note hints are
    grey, so an empty note has no bright pixel where the text would be (measured: 0 against
    6,400 for a five-line note)."""
    from PIL import Image
    import io
    im = Image.open(io.BytesIO(adb('exec-out', 'screencap', '-p').stdout)).convert('L')
    bright = sum(im.crop((40, 300, 1040, 1300)).histogram()[200:])
    return bright < 200


def main():
    mine = read_prefs()
    try:
        write_prefs(with_theme(mine, TERMUX))
        new_note()
        shot('01-notes.png')

        # Ctrl armed: the grid over the keyboard.
        tap(*bar_key(1, 1))
        shot('02-chord.png')
        tap(*bar_key(1, 1))  # and released

        # Another theme, Ctrl armed so its accent shows. Same note.
        write_prefs(with_theme(mine, DRACULA))
        tap(*bar_key(1, 1))
        shot('06-themes.png')
        tap(*bar_key(1, 1))
        write_prefs(with_theme(mine, TERMUX))

        # Another app entirely: a search box.
        sh('input keyevent 4')
        time.sleep(1)
        sh('am start -a android.intent.action.VIEW -d https://duckduckgo.com/')
        time.sleep(6)
        tap(928, 1664)  # the browser promo's close button, if it is up
        tap(540, 920)   # the search box; adjust if the page moves it
        time.sleep(1.5)
        type_text('android ctrl key')
        time.sleep(2)
        shot('03-browser.png')

        # Pinned, on a screen with no text field.
        sh('input keyevent 4')  # back: keyboard away
        time.sleep(1)
        sh(f'su -c "am start-foreground-service -n {PKG}/.OverlayService -a {PKG}.TOGGLE"')
        time.sleep(2)
        shot('04-pinned.png')
        sh(f'su -c "am start-foreground-service -n {PKG}/.OverlayService -a {PKG}.TOGGLE"')

        sh(f'am start -n {PKG}/.MainActivity')
        time.sleep(3)
        shot('05-settings.png')
    finally:
        # Whatever happened, the phone's own settings come back.
        write_prefs(mine)


if __name__ == '__main__':
    main()
