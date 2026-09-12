package dev.noblebits.overkey

import android.app.UiAutomation
import android.graphics.Rect
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Optional key injector. Runs outside the app as root or shell (adb), where
 * InputManager.injectInputEvent is allowed, and delivers keys to whatever window has focus.
 *
 * Launched with:
 *   CLASSPATH=<base.apk> app_process /system/bin dev.noblebits.overkey.Injector <token>
 *
 * Protocol: a mutual challenge-response over the shared token (see [handshake]), so the token
 * itself never crosses the socket and a process squatting the port learns nothing. Then one
 * text line per event: "action keycode metaState repeat", or "m x y button" for a mouse click,
 * or a drag as "d x y" (left button down there), "t x y" lines along the way and "u" (up).
 * "x x y" asks for the text under a point and is answered with "x <base64 utf-8>", empty when
 * there is none. The other way, the injector sends "v <mask>" whenever a volume key goes down
 * or up, bit 0 for Vol- and bit 1 for Vol+: root and shell can read /dev/input, the app cannot.
 */
object Injector {
    const val PORT = 27301
    /** Bumped whenever the protocol changes; a running injector with another version is stale. */
    const val VERSION = 5
    private const val NONCE = 16
    private const val MAC = 32

    private val clients = ArrayList<OutputStream>()

    @JvmStatic
    fun main(args: Array<String>) {
        val token = if (args.isNotEmpty()) args[0] else ""
        val im = inputManager()
        val inject = im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
        // Fails if another injector already owns the port, which is the intent.
        val server = ServerSocket(PORT, 2, InetAddress.getByName("127.0.0.1"))
        watchVolumeKeys()
        while (true) {
            val s = server.accept()
            Thread { serve(s, token, im, inject) }.start()
        }
    }

    /**
     * One reader per input device. A key event is `struct input_event`: a timeval, then
     * type, code and value. Volume keys are KEY_VOLUMEUP (115) and KEY_VOLUMEDOWN (114).
     */
    private fun watchVolumeKeys() {
        val eventSize = if ((System.getProperty("os.arch") ?: "").contains("64")) 24 else 16
        val timeSize = eventSize - 8
        for (i in 0 until 32) {
            val f = File("/dev/input/event$i")
            if (!f.canRead()) continue
            Thread {
                var mine = 0 // the bits this device holds down
                try {
                    val inp = DataInputStream(FileInputStream(f))
                    val buf = ByteArray(eventSize)
                    while (true) {
                        inp.readFully(buf)
                        val type = (buf[timeSize].toInt() and 0xFF) or ((buf[timeSize + 1].toInt() and 0xFF) shl 8)
                        if (type != 1) continue // EV_KEY
                        val code = (buf[timeSize + 2].toInt() and 0xFF) or ((buf[timeSize + 3].toInt() and 0xFF) shl 8)
                        val value = buf[timeSize + 4].toInt() and 0xFF
                        if (value == 2) continue // auto-repeat
                        val bit = when (code) {
                            114 -> 1
                            115 -> 2
                            else -> continue
                        }
                        mine = if (value == 1) mine or bit else mine and bit.inv()
                        volume(bit, value == 1)
                    }
                } catch (_: Exception) {
                    // The device went away (a headset unplugged, say) with a key down, as
                    // far as anyone knows: let go of what it held.
                    if (mine and 1 != 0) volume(1, false)
                    if (mine and 2 != 0) volume(2, false)
                }
            }.start()
        }
    }

    private var mask = 0 // volume keys down across all devices; guarded by clients

    /** One volume key went down or up on some device; clients hear about a change. */
    private fun volume(bit: Int, down: Boolean) {
        synchronized(clients) {
            val now = if (down) mask or bit else mask and bit.inv()
            if (now == mask) return
            mask = now
            broadcast("v $now\n")
        }
    }

    private fun broadcast(line: String) {
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        synchronized(clients) {
            val it = clients.iterator()
            while (it.hasNext()) {
                try {
                    val o = it.next()
                    o.write(bytes)
                    o.flush()
                } catch (_: Exception) {
                    it.remove()
                }
            }
        }
    }

    private fun serve(s: Socket, token: String, im: Any, inject: java.lang.reflect.Method) {
        val downTimes = LongArray(KeyEvent.getMaxKeyCode() + 1)
        val mouse = Mouse(im, inject)
        var out: java.io.OutputStream? = null
        try {
            s.tcpNoDelay = true
            s.soTimeout = 3000 // an unauthenticated peer gets three seconds, then the thread is gone
            val inp = DataInputStream(s.getInputStream())
            out = s.getOutputStream()
            if (!handshake(inp, out, token, server = true)) return
            out.write("hello $VERSION\n".toByteArray(StandardCharsets.UTF_8))
            out.flush()
            s.soTimeout = 0
            synchronized(clients) { clients.add(out) }
            val r = BufferedReader(InputStreamReader(inp))
            val p = IntArray(4)
            while (true) {
                val line = r.readLine() ?: break
                if (line.startsWith("m ")) {
                    if (parse(line.substring(2), p, 3)) mouse.click(p[0].toFloat(), p[1].toFloat(), p[2])
                    continue
                }
                if (line.startsWith("d ")) {
                    if (parse(line.substring(2), p, 2)) mouse.press(p[0].toFloat(), p[1].toFloat())
                    continue
                }
                if (line.startsWith("t ")) {
                    if (parse(line.substring(2), p, 2)) mouse.move(p[0].toFloat(), p[1].toFloat())
                    continue
                }
                if (line == "u") {
                    mouse.release()
                    continue
                }
                if (line.startsWith("x ")) {
                    val text = if (parse(line.substring(2), p, 2)) textAt(p[0], p[1]) else null
                    val b64 = if (text == null) "" else Base64.encodeToString(text.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                    out.write("x $b64\n".toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                    continue
                }
                if (!parse(line, p, 4)) continue
                val action = p[0]
                val code = p[1]
                if (code < 0 || code >= downTimes.size) continue
                val meta = p[2]
                val repeat = p[3]
                val now = SystemClock.uptimeMillis()
                if (action == KeyEvent.ACTION_DOWN && repeat == 0) downTimes[code] = now
                val ev = KeyEvent(downTimes[code], now, action, code, repeat, meta,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                inject.invoke(im, ev, 0) // INJECT_INPUT_EVENT_MODE_ASYNC
            }
        } catch (_: Exception) {
        } finally {
            // Not s.getOutputStream() here: on a closed socket that throws, and the close
            // below would be skipped.
            if (out != null) synchronized(clients) { clients.remove(out) }
            try { mouse.release() } catch (_: Exception) {} // a client gone mid-drag leaves no button down
            try { s.close() } catch (_: Exception) {}
        }
    }

    /**
     * Both sides prove they hold the token without sending it. Client sends a nonce; server
     * answers with its own nonce and HMAC(token, 's' + client nonce); client checks that and
     * answers HMAC(token, 'c' + server nonce). Returns false on any mismatch or short read.
     */
    fun handshake(inp: DataInputStream, out: OutputStream, token: String, server: Boolean): Boolean {
        val mine = ByteArray(NONCE)
        SecureRandom().nextBytes(mine)
        val theirs = ByteArray(NONCE)
        val proof = ByteArray(MAC)
        if (server) {
            inp.readFully(theirs)
            out.write(mine)
            out.write(hmac(token, 's', theirs))
            out.flush()
            inp.readFully(proof)
            return MessageDigest.isEqual(proof, hmac(token, 'c', mine))
        } else {
            out.write(mine)
            out.flush()
            inp.readFully(theirs)
            inp.readFully(proof)
            if (!MessageDigest.isEqual(proof, hmac(token, 's', mine))) return false
            out.write(hmac(token, 'c', theirs))
            out.flush()
            return true
        }
    }

    private fun hmac(token: String, side: Char, nonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        mac.update(side.code.toByte())
        mac.update(nonce)
        return mac.doFinal()
    }

    /**
     * One client's mouse. A desktop-style click gives apps the focus and selection semantics
     * of a real pointer (an image in a browser becomes the thing Ctrl+C copies), which a
     * touch tap does not. A click is down with the button, a press, a release, up; a drag
     * keeps the button held across moves until the release.
     */
    private class Mouse(private val im: Any, private val inject: java.lang.reflect.Method) {
        private val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        private val coords = arrayOf(MotionEvent.PointerCoords().apply { pressure = 1f; size = 1f })
        // setActionButton is hidden; this process is not subject to the hidden API list.
        private val setActionButton = MotionEvent::class.java.getMethod("setActionButton", Integer.TYPE)
        private var downTime = 0L
        private var held = 0 // the button down for a drag in progress, else 0

        fun click(x: Float, y: Float, button: Int) {
            release()
            val state = if (button == 2) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
            press(x, y, state)
            release()
        }

        fun press(x: Float, y: Float, state: Int = MotionEvent.BUTTON_PRIMARY) {
            release()
            coords[0].x = x
            coords[0].y = y
            downTime = SystemClock.uptimeMillis()
            held = state
            send(MotionEvent.ACTION_DOWN, state)
            send(MotionEvent.ACTION_BUTTON_PRESS, state, state)
            SystemClock.sleep(12)
        }

        /** Paced like a hand: an app that only sees the end points of a drag treats it as a click. */
        fun move(x: Float, y: Float) {
            if (held == 0) return
            coords[0].x = x
            coords[0].y = y
            send(MotionEvent.ACTION_MOVE, held)
            SystemClock.sleep(12)
        }

        fun release() {
            if (held == 0) return
            send(MotionEvent.ACTION_BUTTON_RELEASE, 0, held)
            send(MotionEvent.ACTION_UP, 0)
            held = 0
        }

        private fun send(action: Int, buttons: Int, actionButton: Int = 0) {
            // Device 0, as scrcpy does for pointers; the virtual keyboard id (-1) is refused here.
            val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, props, coords, 0, buttons,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            if (actionButton != 0) setActionButton.invoke(ev, actionButton)
            inject.invoke(im, ev, 0)
            ev.recycle()
        }
    }

    private val uiThread by lazy { HandlerThread("ui").apply { start() } }

    /**
     * The text under a screen point, from the accessibility tree of the window in front.
     * Read through [UiAutomation], which only root and shell may connect (the app cannot),
     * connected for the one read: two cannot be connected at once, and a held connection
     * would lock out adb's uiautomator. Not `uiautomator dump`: that waits for the screen
     * to go idle, and a status bar that keeps redrawing (a network speed readout) means it
     * never does. Of the nodes under the point that carry text, the smallest wins: the
     * message over the bubble over the list. A content description stands in for text.
     * Null when there is none or the window is secure. The connection and disconnect are
     * hidden API, reached by reflection as this process may.
     */
    private fun textAt(x: Int, y: Int): String? {
        try {
            val conn = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance()
            val ua = UiAutomation::class.java
                .getConstructor(Looper::class.java, Class.forName("android.app.IUiAutomationConnection"))
                .newInstance(uiThread.looper, conn)
            UiAutomation::class.java.getMethod("connect").invoke(ua)
            try {
                // The active window is known a moment after connecting, so a few tries 50 ms
                // apart. A web view builds its page tree only once it sees a service connect,
                // and until then the whole page is one node described "Web View": a hit that
                // is only a description gets six more looks 300 ms apart for real text.
                var hit: Hit? = null
                var tries = 0
                while (tries++ < 20) {
                    val root = ua.rootInActiveWindow
                    if (root != null) {
                        hit = textAt(root, x, y)
                        if (hit != null && !hit.desc) break
                        if (hit != null && tries > 6) break
                    }
                    SystemClock.sleep(if (hit == null) 50 else 300)
                }
                return hit?.text
            } finally {
                UiAutomation::class.java.getMethod("disconnect").invoke(ua)
            }
        } catch (_: Throwable) {
            return null
        }
    }

    private class Hit(val text: String, val desc: Boolean)

    /** The smallest node under the point with text; failing that, the smallest with a description. */
    private fun textAt(root: AccessibilityNodeInfo, x: Int, y: Int): Hit? {
        val r = Rect()
        val stack = ArrayList<AccessibilityNodeInfo>()
        stack.add(root)
        var text: String? = null
        var desc: String? = null
        var textArea = Long.MAX_VALUE
        var descArea = Long.MAX_VALUE
        while (stack.isNotEmpty()) {
            val n = stack.removeAt(stack.size - 1)
            n.getBoundsInScreen(r)
            // A child can lie outside its parent (a list's off-screen rows do), so the walk
            // does not stop at a parent that misses the point.
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            if (!r.contains(x, y)) continue
            val area = r.width().toLong() * r.height()
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            if (!t.isNullOrEmpty() && area < textArea) {
                textArea = area
                text = t
            } else if (t.isNullOrEmpty() && !d.isNullOrEmpty() && area < descArea) {
                descArea = area
                desc = d
            }
        }
        return if (text != null) Hit(text, false) else if (desc != null) Hit(desc, true) else null
    }

    /** Parses [n] space-separated ints into [out]. Hand-rolled so R8 does not pull in Kotlin's split. */
    private fun parse(line: String, out: IntArray, n: Int): Boolean {
        var got = 0
        var v = 0
        var neg = false
        var digits = 0
        for (i in 0..line.length) {
            val ch = if (i < line.length) line[i] else ' '
            when {
                ch == ' ' -> if (digits > 0) {
                    if (got == n) return false
                    out[got++] = if (neg) -v else v
                    v = 0
                    neg = false
                    digits = 0
                }
                ch == '-' && digits == 0 -> neg = true
                ch in '0'..'9' -> {
                    v = v * 10 + (ch - '0')
                    digits++
                }
                else -> return false
            }
        }
        return got == n
    }

    private fun inputManager(): Any {
        // Android 14 moved the static instance to InputManagerGlobal.
        val cls = try {
            Class.forName("android.hardware.input.InputManagerGlobal")
        } catch (_: ClassNotFoundException) {
            Class.forName("android.hardware.input.InputManager")
        }
        return cls.getMethod("getInstance").invoke(null)!!
    }
}
