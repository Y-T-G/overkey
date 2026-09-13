# Play Store listing

The text that goes with the graphics in this folder. Character limits are Google's.

This file is the source. `tools/build-store-page.py` parses the three fields below straight
out of it to build `review-page.html`, so edit them here and run that again rather than
editing the page.

## App name (30)

`Overkey - Ctrl, Alt and arrows`

## Short description (80)

`Modifier and arrow keys over the keyboard you already use. No root needed.`

## Full description (4000)

Overkey gives you the keys a phone keyboard leaves out, without replacing the keyboard. Ctrl, Alt, Shift, Meta, Esc, Tab, Home, End, Page Up, Page Down and the four arrows sit in a bar that appears above whatever keyboard you have open and goes away with it.

Ctrl+C, Ctrl+Z, Ctrl+A.
Tap Ctrl and a grid of letters, digits and symbols is laid over the keyboard. Tap C on it and the app in front gets Ctrl+C. Hold Ctrl and tap several keys for a run of them. Long-press to lock a modifier down, tap it again to let go.

It works in any app.
Keys are delivered the way a hardware keyboard's are, so a terminal, a code editor, a browser, a spreadsheet and a chat app all see the same thing. The bar is not an input method, so your keyboard, its layout and its dictionary stay exactly as they are.

Setting up.
The bar is an overlay, so it needs "Display over other apps". Delivering keys needs a small helper started once per boot, in one of three ways: Shizuku, which needs no root and no PC after the first setup; one adb command from a PC; or root. The settings screen walks through each and says whether the helper is running.

Volume keys as modifiers.
While a keyboard is up or the bar is pinned, holding Volume Down holds Ctrl, the same as in Termux. Volume Up and both together can be bound to any modifier or left alone. The volume stays put while a bound key is at work. Elsewhere the volume keys do what they always did.

Also in the app:
- Pinned mode. Tap the notification and the bar appears with no keyboard, for Ctrl+Z on a screen with no text field.
- Aim mode. Long-press the bar's handle, then tap anywhere for a mouse click, or drag for a mouse drag. In a browser a drag selects text instead of scrolling. Long-press for a menu: a right click, Select text, which lifts the text under your finger into a panel where you can select part of it and copy, or Copy image, which puts the picture under your finger on the clipboard as it appears on screen. Both work where the app offers no copy at all, a chat message or a photo in it.
- Share to clipboard. Overkey shows up in any app's share sheet. Share text, a picture or a file and it goes on the clipboard, so the next Ctrl+V pastes it, in an app that offers no copy of its own.
- A handle to drag the bar up or down, tap to fold it away to a pill, and a hidden mode where nothing is on screen but the volume keys still work.
- Colours from your wallpaper by default, as the rest of Android 12 does, or eleven editor themes: Termux, Dracula, Monokai, One Dark, Nord, Gruvbox, Solarized, Catppuccin, Tokyo Night, GitHub Light. Opacity for the grid and its letters, row height, and calibration of the grid's edges so its letters line up with your keyboard's.
- Hold Tab for a real tab character where a tap would move focus.

What it does not do.
It collects nothing, sends nothing anywhere and has no accounts. The only network connection is to the helper on the phone itself. There is no accessibility service. The download is under 100 KB.

## Screenshot captions

In the order they are uploaded. Captions are burned into the images by
`tools/build-store-shots.py`, so change them there. The raw captures come from
`tools/capture-store-shots.py`, which drives the phone over adb (a Keep note, the browser,
pinned mode, the settings screen) and puts the phone's own settings back when it is done.
Play keeps every screenshot it has been given and will not overwrite one, so each release
writes its own directory and carries the version code in the file name too.

| File | Caption |
| --- | --- |
| `01-notes-v1.png` | Modifier keys over your own keyboard |
| `02-chord-v1.png` | Ctrl+C from a grid over the keyboard |
| `03-browser-v1.png` | Works in any app |
| `04-pinned-v1.png` | Ctrl+Z with no keyboard open |
| `05-settings-v1.png` | Root, Shizuku or one adb command |
| `06-themes-v1.png` | Twelve themes, or your own accent |

## Sizes

| Asset | Size | File |
| --- | --- | --- |
| Phone screenshots | 1080 x 1920 | `v1/0*-v1.png`, six of them |
| Feature graphic | 1024 x 500 | `v1/feature-graphic-v1.png` |
| App icon | 512 x 512 | `icon-512.png` |

The icon, the feature graphic and the site's link preview come from
`tools/build-store-assets.py`, which reads the keycap from the launcher icon's vector
drawables, so the store icon is the phone's icon at 512 pixels.

## Data safety

What to answer in the Play Console form. Nothing is collected and nothing is shared, and
every row of the form is No. What follows is why, with the file that shows it, so the next
release can check the answer rather than remember it.

| Data type | Collected | Shared | Where |
| --- | --- | --- | --- |
| Any | No | No | There is no network code outside `InjectorClient.kt` and `Injector.kt`, and both only ever open `127.0.0.1:27301` |

The app declares `INTERNET`, which the form does not ask about but a reviewer might. It is
needed to open a socket at all, and the socket is loopback: `InjectorClient.kt` connects to
`127.0.0.1` and `Injector.kt` binds `127.0.0.1`, so nothing can leave the phone by it. The
connection carries key events from the app to the helper and volume key state back.

`SYSTEM_ALERT_WINDOW` is the bar. `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`
keep the service that watches for the keyboard alive; the Console asks for a reason for the
special-use type, and it is: the service polls the keyboard's insets and owns the overlay,
which no other foreground service type describes. `POST_NOTIFICATIONS` is the one
notification, which is the only way to pin the bar. `RECEIVE_BOOT_COMPLETED` restarts the
service. There is no accessibility service, no analytics SDK, no advertising id, no location,
no contacts, no crash reporting and no third-party code except the Shizuku client library,
which talks to the Shizuku app on the same phone.

The helper injects input events, which is what Shizuku exists for and what adb does; the
listing says so in plain words so the review is not surprised by it. Root is one of three
ways to start it and the app works fully without root.

Deletion: there is nothing on any server to delete. Uninstalling removes the app's
preferences, which are the settings and a random token the helper is authenticated with.
