package dev.noblebits.overkey

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowManager
import rikka.shizuku.Shizuku

/**
 * Runs [Overlays]. Needs "display over other apps" and the injector, nothing else.
 *
 * Keyboard height comes from [WindowManager.getCurrentWindowMetrics]: the system computes
 * the insets a plain app window would get right now, IME included, so no window of our own
 * is needed. Nothing pushes that value, so it is polled while the screen is on. An invisible
 * full-screen window was tried first and rejected: overlay windows of other apps get no IME
 * insets on Android 12, and one with alpha above 0.8 blocks every touch underneath.
 */
class OverlayService : Service() {

    private var overlays: Overlays? = null
    private lateinit var wm: WindowManager
    private var pinned = false // bar shown at the screen bottom even with no keyboard
    private val binderArrived = Shizuku.OnBinderReceivedListener { overlays?.client?.ensure(launch = true) }
    private val handler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var lostOnce = false
    private val poll = object : Runnable {
        override fun run() {
            look()
            handler.postDelayed(this, POLL_MS)
        }
    }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(poll)
            if (intent.action == Intent.ACTION_SCREEN_ON) handler.post(poll) else overlays?.keyboard(Rect())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Android 15 can refuse a foreground start from the background (a sticky restart
        // after low memory, say); a refusal must not take the process down.
        try {
            startForeground(1, notification())
        } catch (_: Exception) {
            stopSelf()
            return
        }
        if (Settings.canDrawOverlays(this)) {
            setUp()
        } else if (Prefs.root(this)) {
            // The permission may have been cleared by a reinstall; root can put it back.
            Thread {
                val ok = InjectorClient.regainOverlay(this)
                handler.post { if (destroyed) Unit else if (ok) setUp() else stopSelf() }
            }.start()
        } else {
            stopSelf()
        }
    }

    private fun setUp() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val o = Overlays(this) { lost() }
        overlays = o
        o.client.ensure(launch = true)
        try { Shizuku.addBinderReceivedListenerSticky(binderArrived) } catch (_: Throwable) {}
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        if ((getSystemService(POWER_SERVICE) as PowerManager).isInteractive) handler.post(poll)
    }

    /**
     * A window could not be added. Usually the permission went away; root can put it back
     * and the service comes back with it. Once per service, and only when the permission
     * really was missing: a ROM that refuses the window for some other reason must not get
     * a service that restarts itself forever.
     */
    private fun lost() {
        if (lostOnce) return
        lostOnce = true
        stopSelf()
        if (!Settings.canDrawOverlays(this) && Prefs.root(this)) {
            val app = applicationContext
            Thread { if (InjectorClient.regainOverlay(app)) startIfWanted(app) }.start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val o = overlays
        if (o != null) when (intent?.action) {
            ACTION_TOGGLE -> {
                pinned = !pinned
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, notification())
                look()
            }
            ACTION_HIDE -> {
                o.hidden = !o.hidden
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, notification())
            }
        }
        return START_STICKY
    }

    /** Dark mode or the wallpaper changed: the system theme follows it. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlays?.reload()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(poll)
        try { Shizuku.removeBinderReceivedListener(binderArrived) } catch (_: Throwable) {}
        try { unregisterReceiver(screen) } catch (_: Exception) {}
        overlays?.destroy()
        overlays = null
        super.onDestroy()
    }

    private fun look() {
        val o = overlays ?: return
        val m = wm.currentWindowMetrics
        val ins = m.windowInsets
        val h = if (ins.isVisible(WindowInsets.Type.ime())) ins.getInsets(WindowInsets.Type.ime()).bottom else 0
        val b = m.bounds
        if (h > 0) {
            o.keyboard(Rect(b.left, b.bottom - h, b.right, b.bottom))
        } else if (pinned) {
            // No keyboard: park the bar just above the navigation bar. With gesture navigation
            // that inset is 0, and an empty rectangle would read as "hide", so keep 1 px.
            val nav = maxOf(ins.getInsets(WindowInsets.Type.navigationBars()).bottom, 1)
            o.keyboard(Rect(b.left, b.bottom - nav, b.right, b.bottom), keyboard = false)
        } else {
            o.keyboard(Rect())
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Overlay", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getForegroundService(this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE)
        val hide = PendingIntent.getForegroundService(this, 2,
            Intent(this, OverlayService::class.java).setAction(ACTION_HIDE), PendingIntent.FLAG_IMMUTABLE)
        val hidden = overlays?.hidden == true
        val none = null as android.graphics.drawable.Icon?
        @Suppress("DEPRECATION")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(when {
                hidden -> "Overkey hidden"
                pinned -> "Bar pinned to the screen"
                else -> "Overkey is running"
            })
            .setContentText(if (pinned) "Tap to unpin" else "Tap to show the bar without a keyboard")
            .setContentIntent(toggle)
            .addAction(Notification.Action.Builder(none, if (hidden) "Show" else "Hide", hide).build())
            .addAction(Notification.Action.Builder(none, "Settings", open).build())
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
    }

    companion object {
        // Not "overlay": that channel was created at a higher importance by an earlier
        // build, and a channel's importance is fixed once the user has seen it.
        private const val CHANNEL = "bar"
        private const val ACTION_TOGGLE = "dev.noblebits.overkey.TOGGLE"
        private const val ACTION_HIDE = "dev.noblebits.overkey.HIDE"
        private const val POLL_MS = 100L

        /** Starts the service if the overlay permission has been granted, or root can grant it. */
        fun startIfWanted(c: Context) {
            if (!Settings.canDrawOverlays(c) && !Prefs.root(c)) return
            val i = Intent(c, OverlayService::class.java)
            c.startForegroundService(i)
        }
    }
}
