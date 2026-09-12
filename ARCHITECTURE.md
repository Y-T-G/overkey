# Overkey Architecture

Modifier and navigation keys floating over whatever on-screen keyboard is in use. Native Kotlin + View system, one dependency (Shizuku API). Package: `dev.noblebits.overkey`

## File Map

```
├── MainActivity.kt      Setup, delivery status, theme, sliders, calibration (programmatic UI, plain style)
├── WordmarkView.kt      The app name drawn with the icon's extrusion; also the shared light vector and blend()
├── Overlays.kt          The bar, its pill, the chord grid and aim mode: layout, modifiers, calibration, delivery
├── OverlayService.kt    Foreground service that polls the keyboard's insets, runs Overlays, owns the notification
├── BootReceiver.kt      Starts the service after a reboot or an update
├── KeyBarView.kt        KeySink, Key, Mods (sticky modifier state), KeyBarView (one canvas per overlay)
├── Prefs.kt             Themes table and SharedPreferences keys
├── InjectorClient.kt    Loopback client for the injector: handshake, root launch, adb command, probe
├── Injector.kt          The injector: app_process entry point, key and mouse injection, volume key watcher
└── ShizukuInjector.kt   Shizuku user service that runs Injector.main as shell

tools/build-store-assets.py   Play icon, feature graphic and the site's link preview, from the icon's vectors
tools/build-store-page.py     docs/store/review-page.html from docs/store/listing.md
docs/store/                   The Play listing: listing.md is the source, the rest is built
```

The site page, `noblebits.dev/overkey/`, lives in the noblebits.dev repository under
`server/public/overkey/` with the rest of the site (Firebase hosting, deployed by that
repository's workflow on push). Its `demo.js` reimplements `Mods` for the bar on the page;
change one and change the other.

## Permissions

"Display over other apps", a foreground service (`specialUse`) with a minimal notification, `POST_NOTIFICATIONS` (requested on 13+ so that notification shows), `RECEIVE_BOOT_COMPLETED`, `INTERNET` for the loopback socket. No accessibility service: declaring one at all puts the app under Play's accessibility policy, so it was removed rather than kept as an option. Battery exemption opens the system list (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), which needs no permission; the direct request intent is a Play review trigger. Root, Shizuku and adb are ways to start the injector, not permissions the app declares.

`minSdk` is 30: `WindowMetrics` and `WindowInsets.Type` are API 30 and nothing works without them.

MIUI clears the `SYSTEM_ALERT_WINDOW` app op on some reinstalls. Once `su` has worked (the
`root` pref, set by a successful root launch), the app puts it back itself with
`appops set ... allow`: at service start, when a window cannot be added, and when the
settings screen opens. A phone without root never runs `su`, so never sees a prompt.

## Keyboard tracking

`OverlayService` polls `WindowManager.getCurrentWindowMetrics().windowInsets` every 100 ms while the screen is on: the system computes the insets a plain app window would get right now, IME included, so no window of our own is needed. `Overlays.keyboard` only relayouts when the rectangle changes.

Two things were tried first and do not work on Android 12: an invisible full-screen overlay never receives the IME inset, and any overlay window with alpha above 0.8 blocks every touch underneath it (the "untrusted touch" rule), even when fully transparent.

All overlay windows are `TYPE_APPLICATION_OVERLAY`. Android layers the keyboard just above the app it serves and overlays above all apps, so the chord grid can lie on the keyboard.

## Overlays

- The bar: `ESC SHIFT META HOME ↑ END PGUP` / `TAB CTRL ALT ← ↓ → PGDN`, on the keyboard's top edge, `barY` dp lower or higher if the user dragged it. There is no automatic avoidance of text fields; the user drags instead.
- The pill: a 14 dp strip along the bottom of the bar with a small handle drawn in it (touches count from 20 dp up). Drag moves the bar, tap collapses the bar to the pill alone, long-press enters aim mode. Collapsed, the pill is its own 80×28 dp window with a 64×20 dp capsule drawn inside, set in an even 1.5 dp surround of 20% accent and a thin rim so it can be found against a dark keyboard; even rather than the settings cards' one-sided wall, because the pill sits anywhere on the screen and a wall pushed one way reads as a slant. Tap expands. Collapsing swaps windows rather than resizing one, because a resize showed a frame with the small window at the old left edge. Collapsing resets the modifiers.
- The chord grid: digits, QWERTY rows with real stagger, symbols, space, backspace, enter, laid over the keyboard so it can be aligned with its letters. Shown only while Ctrl, Alt or Meta is armed, because the real keyboard commits text, not key events, so it cannot take part in a chord. Its four edges are offsets from the keyboard rectangle, set by dragging in calibrate mode. Pinned (below), there is no keyboard to lie on, so it stacks above the bar and moves with it, or below the bar when the bar sits too close to the status bar for the grid to fit above.
- Aim mode: a full-screen tinted window that takes one gesture. Tap sends a mouse left click at that spot through the injector, a drag a left-button drag along the finger's path (drawn as a trail while the finger is down), a second finger cancels. Long-press opens a two-pill menu at the finger, drawn by the same view: Right click, or Select text. The second asks the injector for the text under the point and shows it in a panel (`showText`) with a selectable `TextView`, COPY and CLOSE; COPY takes the selection if there is one, else all of it. The panel is a `Dialog` on the overlay window type with `FLAG_ALT_FOCUSABLE_IM`, so the keyboard behind stays up and Ctrl+C from the bar reaches the panel. A Dialog rather than a view added to the window manager because the floating selection toolbar is an action mode and only a `DecorView` starts one; without it the handles appeared and the toolbar did not. This exists because a chat bubble's text is selectable by no pointer at all, mouse included: the app only offers the whole message on long press. The drag is replayed after the finger lifts, not sent live: the finger is still down on the aim window, and before Android 14 the input dispatcher holds one pointer device at a time, so a mouse DOWN injected mid-touch gets the touch stream dropped. The path is thinned to at most 32 points and the injector paces them 12 ms apart, because an app that only sees the end points takes the drag for a click. Everything is delayed 150 ms after the window is removed, or it lands on the window itself; the delay runs on a main-thread `Handler`, not `View.postDelayed`, because when the bar is collapsed that view is detached and a detached view never runs what is posted to it. A mouse gesture has desktop semantics that a touch does not: in Chromium a right click on an image opens the context menu with Copy image, and a drag selects text where a touch drag scrolls.

Both use `OverlayFade`: 120 ms fade in, 60 ms fade out. All modifiers reset when the keyboard goes away; every key still held is released when a view is detached, so no app is left with a key down.

## Pinned mode

Tapping the notification toggles the bar on with no keyboard, parked just above the navigation bar (with gesture navigation that inset is 0, so the stand-in rectangle is kept 1 px tall or it would read as "hide"). The keys still go to whatever window has focus, so Ctrl+Z works on screens with no text field. When a keyboard does appear the bar moves above it as usual. The notification has two actions: Hide/Show, which toggles hidden mode (no window at all, the volume keys still work), and Settings. It is `IMPORTANCE_MIN`, `PRIORITY_MIN`, `VISIBILITY_SECRET` and alerts once, which is as far out of the way as a foreground service's notification goes.

## Volume keys

The injector watches `/dev/input/event*` for KEY_VOLUMEDOWN (114) and KEY_VOLUMEUP (115), one thread per readable device, and sends `v <mask>` (bit 0 Vol-, bit 1 Vol+) whenever the mask changes. The app cannot read those files; root and shell can. `Overlays.volumeKeys` maps the mask to the bound modifier keys, Vol- to Ctrl by default (Termux's convention), Vol+ and both-together off, each settable to Off/Ctrl/Alt/Meta/Shift. A held volume key is a finger on that bar key (`Key.held`, `mods.press`), but letting go calls `release(k, arm = false)`: unlike a tap on the bar it never leaves the modifier armed. Only while the bar has a place, a keyboard up or the bar pinned; the mask is cleared when that goes, so a key held across it does not stay down. While a bound key has a job the app sends `g <mask>` and the injector grabs every input device that has a volume key (`EVIOCGRAB`, found through `/sys/class/input/eventN/device/capabilities/key`), so the system never sees the press and the volume stays put. A grab takes the whole device, and the power button shares one with Vol- on this phone (`qpnp_pon`), so every other key on a grabbed device is relayed as an injected `KeyEvent`, repeats included; the phone still locks and wakes. The only ioctl reachable from Java is the hidden `Os.ioctlInt`, which passes a pointer as the argument, so it can grab (any non-zero) but not release (zero): the grab is set on open and dropped by closing and reopening the device. It is dropped whenever the last client disconnects.

## Keys

`TAB` is special: a plain Tab moves focus in text fields, by design of `TextView`. Held for 450 ms it is sent with `META_SYM_ON`, which `TextView` does not treat as navigation but the key character map still resolves to `\t`, so a literal tab is inserted. Terminals give `\t` for both.

## Modifiers

`Mods` is shared by all views. Tap arms a modifier for the next key, long-press locks it, tap again releases. Holding one finger on a modifier and tapping another key is a chord; a hold that was used in a chord does not lock. The modifier's own down event is sent when it becomes active and its up event when it stops, so the app sees the same sequence a physical keyboard produces. Normal keys carry the active meta state and auto-repeat while held.

## Key delivery

Everything goes through the injector over `127.0.0.1:27301`. Any window, any key. It needs root (launched with `su`), Shizuku (user service, shell uid, `daemon=true`) or `adb shell` from a PC. The service launches it on connect and on Shizuku binder arrival; `ensure(launch = true)` connects first and only launches when that fails, otherwise every reconnect would spawn another `su`. The launch runs on its own thread because `su` can block on a permission prompt. Without an injector the bar shows but keys go nowhere, and the settings screen says so.

The connection is a mutual challenge-response over a UUID token stored in prefs and passed to the injector on its command line: client nonce, server nonce plus HMAC-SHA256(token, 's' + client nonce), client HMAC(token, 'c' + server nonce). The token never crosses the socket, so a process squatting the port learns nothing, and the client can tell our injector from a stale one left by an earlier install. After the handshake the injector writes `hello <VERSION>`; a running injector from an older build has the right token but the wrong version, and is treated as foreign and replaced. `probe` runs the same greeting for the status line. When the socket hits EOF (reboot, kill, update) the client relaunches after a second, at most once every ten seconds so an injector that cannot start does not loop. Root launches `pkill -f 'overkey.[I]njector'` first, in its own `su` call: in one line with the launch, `pkill -f` matches that line too and kills its own shell. Shizuku daemons found holding the port are stopped with `unbindUserService(..., true)` before a rebind.

The client keeps a reader thread on the socket for the `v` lines, so it learns the injector is gone as soon as it goes rather than at the next write.

## Injector

`Injector.main` runs under `app_process` with the APK on the classpath. It gets `InputManager` (`InputManagerGlobal` on 14+) by reflection and calls `injectInputEvent(event, 0)`. Hidden API checks do not apply to a process started this way. After the handshake, one text line per event: `action keycode meta repeat`, or `m x y button` for a mouse click, which is sent as DOWN, BUTTON_PRESS, BUTTON_RELEASE, UP with `SOURCE_MOUSE`, tool type mouse and device id 0 (the virtual keyboard id is refused for pointer events); `setActionButton` is hidden API and reached by reflection. A drag is `d x y` (left button down), `t x y` per point and `u` (up); the button is held per client and released if the client goes away mid-drag. `x x y` asks for the text under a point, answered `x <base64 utf-8>` (empty for none): the injector connects a `UiAutomation` (hidden constructor and `connect`/`disconnect` by reflection; root and shell may, the app may not), takes `getRootInActiveWindow`, walks the whole tree (children can lie outside their parent's bounds) and picks the smallest node under the point with text, else the smallest with a content description, then disconnects, since two cannot be connected at once and a held one would lock out adb's uiautomator. A hit that is only a description gets up to six more looks 300 ms apart: a web view builds its page tree only after it sees a service connect, and until then the page is one node described "Web View". Not `uiautomator dump`: it waits for the screen to go idle, and a status bar that keeps redrawing (MIUI's network speed) means it never does. The other way, `v <mask>` for the volume keys. Unauthenticated peers get a 3 s socket timeout. Same binary serves root, Shizuku and adb. `VERSION` is bumped whenever any of this changes.

## Settings

`theme` (index into `Prefs.THEMES`; 0 is "System accent", the default, resolved from `android.R.color.system_accent1_*` and `system_neutral1_*` on 12+ following dark mode, Termux below that; the service reloads on `onConfigurationChanged`), `alpha` and `fg_alpha` (grid background and text, 0..255), `row` (bar row height dp), `bar_y`, `top`, `bottom`, `left`, `right` (dp offsets), `vol_down`, `vol_up`, `vol_both` (index into `VOL_MODS`), `root` (su has worked once). `MainActivity` writes them and calls `Overlays.instance?.reload()`; same process, so a static field is enough.

## Look

Settings screen colours come from the launcher icon: ground `#0B0D14`, surface `#1A1E2A`, copy `#A7B0C2`, accent `#5FB0F0`. Cards use a plinth: one copy of the card shape pushed 3dp along the light vector (0.62, 0.78), coloured accent blended 45% toward the surface, and a 1dp rim on the top-left edge. The wordmark is the same recipe in text. The icon is one keycap extruded along the same vector with the caret terminals use for Ctrl.

## Size

Release APK 48 KB at 1.0.0, R8 minified with `-allowaccessmodification` and `-repackageclasses`, `kotlin/**` metadata excluded, Kotlin's parameter null-check calls off, dex compressed (`dex.useLegacyPackaging`, AGP stores it uncompressed from minSdk 28). Kotlin constructs that drag in whole runtime classes are avoided: `split`, `withIndex`, `use`, function types (`Runnable` and `Consumer` instead), captured `var`s, and `enum class` (its `EnumEntries` initialiser alone added 30 KB). The Shizuku client is the largest remaining block, about 20 classes. Debug builds are not optimised by R8, so their size says nothing.

## Review notes

Fable reviewed the code once; what is not fixed:

- Insets from a service context on Android 13 to 15, and floating keyboards (they report an IME inset of 0, so the bar never appears in those modes), need a device to check.
- On Android 15 a `START_STICKY` restart from the background can be refused; `startForeground` is wrapped so the process does not die, but the overlay is then gone until the app is opened.
- The overlay sits above the keyboard only because the keyboard is layered relative to its target activity; when the target is not an activity (a system dialog) the keyboard is drawn above the bar.

## Release notes

- AGP 8.7.3 with compileSdk 36 needs `android.suppressUnsupportedCompileSdk=36`.
- Release builds are signed with the debug key for now; there is no release keystore yet.
- MIUI blocks `adb install` until the prompt on the phone is accepted, and resets the app's `SYSTEM_ALERT_WINDOW` appop on every reinstall (`adb shell appops set dev.noblebits.overkey SYSTEM_ALERT_WINDOW allow`).
- The pinned toggle from a shell: `su -c 'am start-foreground-service -n dev.noblebits.overkey/.OverlayService -a dev.noblebits.overkey.TOGGLE'` (plain `adb shell` is refused, the service is not exported).
- Store graphics: `python tools/build-store-assets.py` (needs Chrome and fontTools), then `python tools/build-store-page.py` for the review page. The listing text is `docs/store/listing.md`; no screenshots yet, the planned set is listed there.
