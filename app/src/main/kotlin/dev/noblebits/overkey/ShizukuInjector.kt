package dev.noblebits.overkey

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

/**
 * Shizuku user service: a process Shizuku starts as shell from this APK. It only has to run
 * [Injector.main], which then serves the usual loopback socket. Started with daemon=true so it
 * outlives the app process.
 */
class ShizukuInjector : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            START -> {
                val token = data.readString() ?: ""
                Thread { try { Injector.main(arrayOf(token)) } catch (_: Throwable) {} }.start()
                return true
            }
            ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy -> System.exit(0)
        }
        return super.onTransact(code, data, reply, flags)
    }

    companion object {
        private const val START = 1
        private var token = ""

        /** One connection for the life of the process; Shizuku keeps every one it is handed. */
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val data = Parcel.obtain()
                try {
                    data.writeString(token)
                    binder.transact(START, data, null, IBinder.FLAG_ONEWAY)
                } catch (_: Throwable) {
                } finally {
                    data.recycle()
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }

        private fun args(pkg: String) = Shizuku.UserServiceArgs(ComponentName(pkg, ShizukuInjector::class.java.name))
            .daemon(true).processNameSuffix("injector").version(Injector.VERSION) // Shizuku replaces a daemon of an older version itself

        fun running(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

        fun granted(): Boolean = running() && try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }

        /** Binds (and so starts) the user service, then hands it the token. */
        fun start(pkg: String, token: String): Boolean {
            if (!granted()) return false
            Companion.token = token
            return try {
                if (Shizuku.peekUserService(args(pkg), connection) < 0) Shizuku.bindUserService(args(pkg), connection)
                true
            } catch (_: Throwable) {
                false
            }
        }

        /** Kills the daemon, for when it holds the port with a token from an earlier install. */
        fun stop(pkg: String) {
            try { Shizuku.unbindUserService(args(pkg), connection, true) } catch (_: Throwable) {}
        }
    }
}
