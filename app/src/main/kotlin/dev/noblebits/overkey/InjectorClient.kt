package dev.noblebits.overkey

import android.content.Context
import android.os.Handler
import android.provider.Settings
import android.view.KeyEvent
import android.os.HandlerThread
import android.os.SystemClock
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Loopback client for [Injector]. All socket work happens on its own thread. */
class InjectorClient(private val context: Context) {

    companion object {
        // What is on the injector port. Plain ints: a Kotlin enum drags EnumEntries into the dex.
        const val NONE = 0
        const val OURS = 1
        const val FOREIGN = 2 // something answered but failed the handshake

        private const val CONNECT_TIMEOUT_MS = 150
        private const val HANDSHAKE_TIMEOUT_MS = 1000
        private const val RETRY_MS = 5000L
        private const val RELAUNCH_MS = 10000L

        fun token(context: Context): String {
            val prefs = context.getSharedPreferences("overkey", Context.MODE_PRIVATE)
            prefs.getString("token", null)?.let { return it }
            val t = UUID.randomUUID().toString()
            prefs.edit().putString("token", t).apply()
            return t
        }

        /** Shell command that starts the injector. Same for `su -c` and `adb shell`. */
        fun launchCommand(context: Context): String =
            "CLASSPATH='${context.applicationInfo.sourceDir}' nohup app_process /system/bin " +
                "dev.noblebits.overkey.Injector ${token(context)} >/dev/null 2>&1 &"

        /**
         * Starts the injector through root, after killing any injector left from an earlier
         * install (it would hold the port with a token that no longer matches). Blocks; call
         * off the main thread.
         */
        fun launchWithRoot(context: Context): Boolean = try {
            // Its own su call: in one line with the launch, pkill -f would match that line too.
            ProcessBuilder("su", "-c", "pkill -f 'overkey.[I]njector'").redirectErrorStream(true).start().waitFor()
            val ok = ProcessBuilder("su", "-c", launchCommand(context)).redirectErrorStream(true).start().waitFor() == 0
            if (ok) Prefs.of(context).edit().putBoolean(Prefs.ROOT, true).apply()
            ok
        } catch (_: Exception) {
            false
        }

        /**
         * Grants "Display over other apps" back with root. Some ROMs (MIUI) clear the app op
         * on every reinstall, and a bar that needs the settings screen after each update is
         * no bar. Only once su has worked, so a phone without root never sees a prompt.
         * Blocks; call off the main thread. True if the permission is there afterwards.
         */
        fun regainOverlay(context: Context): Boolean {
            if (Settings.canDrawOverlays(context)) return true
            if (!Prefs.root(context)) return false
            try {
                ProcessBuilder("su", "-c", "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
                    .redirectErrorStream(true).start().waitFor()
            } catch (_: Exception) {
                return false
            }
            return Settings.canDrawOverlays(context)
        }

        /** Connects and runs the handshake, then closes. For status only. */
        fun probe(context: Context): Int {
            val s = Socket()
            return try {
                try {
                    s.connect(InetSocketAddress("127.0.0.1", Injector.PORT), CONNECT_TIMEOUT_MS)
                } catch (_: IOException) {
                    return NONE
                }
                s.soTimeout = HANDSHAKE_TIMEOUT_MS
                // Something holds the port. An injector from a build before the version line
                // passes the handshake and then says nothing, which is a timeout here, and
                // it is foreign all the same: it has to be replaced, not waited for.
                if (greet(s, context)) OURS else FOREIGN
            } catch (_: IOException) {
                FOREIGN
            } finally {
                try { s.close() } catch (_: IOException) {}
            }
        }

        /** Handshake plus the version line; false for a foreign or outdated injector. */
        private fun greet(s: Socket, context: Context): Boolean {
            val inp = DataInputStream(s.getInputStream())
            if (!Injector.handshake(inp, s.getOutputStream(), token(context), server = false)) return false
            val line = StringBuilder()
            while (true) {
                val b = inp.read()
                if (b < 0) return false
                if (b == '\n'.code) break
                line.append(b.toChar())
                if (line.length > 32) return false
            }
            return line.toString() == "hello ${Injector.VERSION}"
        }
    }

    private val thread = HandlerThread("overkey-inject").apply { start() }
    private val handler = Handler(thread.looper)
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var lastAttempt = 0L
    private var lastRelaunch = 0L

    @Volatile var connected = false
        private set

    /** Volume keys held right now, bit 0 Vol- and bit 1 Vol+, on the main thread. */
    var onVolumeKeys: java.util.function.Consumer<Int>? = null
    private val main = Handler(android.os.Looper.getMainLooper())

    /**
     * Connects if needed. With [launch], also tries to start the injector: root first, then
     * Shizuku. The launch runs on its own thread, because `su` can block on a permission
     * prompt for seconds and keys are queued on this one.
     */
    fun ensure(launch: Boolean = false) {
        handler.post {
            if (connect(force = launch)) return@post
            if (!launch) return@post
            // The launch kills whatever holds the port, and that shows up as an EOF on
            // the reader; marked as a relaunch now, so the EOF does not start another.
            lastRelaunch = SystemClock.uptimeMillis()
            Thread {
                if (probe(context) == FOREIGN) ShizukuInjector.stop(context.packageName)
                if (launchWithRoot(context) || ShizukuInjector.start(context.packageName, token(context))) {
                    handler.postDelayed({ connect(force = true) }, 700)
                    handler.postDelayed({ connect(force = true) }, 2500)
                }
            }.start()
        }
    }

    fun send(action: Int, code: Int, meta: Int, repeat: Int) = write("$action $code $meta $repeat\n")

    /** A mouse click at screen coordinates; [button] is 1 for primary, 2 for secondary. */
    fun click(x: Int, y: Int, button: Int) = write("m $x $y $button\n")

    private fun write(line: String) {
        handler.post {
            val o = out ?: (if (connect()) out!! else return@post)
            try {
                o.write(line.toByteArray(StandardCharsets.UTF_8))
                o.flush()
            } catch (_: IOException) {
                close()
                connect()
            }
        }
    }

    fun shutdown() {
        handler.post { close() }
        thread.quitSafely()
    }

    /** [force] skips the retry limit that keeps key presses from hammering a dead port. */
    private fun connect(force: Boolean = false): Boolean {
        if (out != null) return true
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastAttempt < RETRY_MS) return false
        lastAttempt = now
        val s = Socket()
        return try {
            s.connect(InetSocketAddress("127.0.0.1", Injector.PORT), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
            val o = s.getOutputStream()
            if (!greet(s, context)) {
                s.close()
                return false
            }
            s.soTimeout = 0
            socket = s
            out = o
            connected = true
            listen(s)
            // The injector this replaces may have died with a modifier down in the app in
            // front; an UP for each costs nothing and clears it.
            for (m in Mods.MODIFIERS) o.write("${KeyEvent.ACTION_UP} ${m.code} 0 0\n".toByteArray(StandardCharsets.UTF_8))
            o.flush()
            true
        } catch (_: IOException) {
            try { s.close() } catch (_: IOException) {}
            false
        }
    }

    /** Reads what the injector sends; an EOF means it died, so the next key does not go missing. */
    private fun listen(s: Socket) {
        Thread {
            try {
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("v ") && line.length == 3) {
                        val mask = line[2] - '0'
                        if (mask in 0..3) main.post { onVolumeKeys?.accept(mask) }
                    }
                }
            } catch (_: IOException) {
            }
            handler.post {
                if (socket === s) {
                    close()
                    main.post { onVolumeKeys?.accept(0) }
                    // The injector went away (reboot, kill, update): bring it back, but not in a
                    // loop if it cannot start.
                    val now = SystemClock.uptimeMillis()
                    if (now - lastRelaunch > RELAUNCH_MS) {
                        lastRelaunch = now
                        handler.postDelayed({ ensure(launch = true) }, 1000)
                    }
                }
            }
        }.start()
    }

    private fun close() {
        connected = false
        out = null
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }
}
