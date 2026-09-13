package dev.noblebits.overkey

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File
import java.io.InputStream

/**
 * The share target. Send anything to Overkey from another app's share sheet and it goes on the
 * clipboard, so the next Ctrl+V pastes it, in apps that offer no copy of their own.
 *
 * Text goes on as plain text. A file comes in as a URI the sender granted this app a read on;
 * that grant does not reach the app that later pastes, so the bytes are copied into the cache
 * and served from [ClipProvider], whose URI the clipboard can grant on. The sender's own name
 * and type for the file are carried across, because a pasting app judges the file by those.
 * Whether a paste lands is the receiving app's call: Android has no general "paste a file into
 * a folder" the way a desktop does, but an image field, a chat box or an editor takes what it
 * understands.
 *
 * No window of its own: it reads the intent, writes the clipboard, says so and finishes.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        val offered = streams(intent)
        val uris = offered.filter { safe(it) }
        // A link shared with a preview picture is a text share: the URL is what was meant, not
        // the thumbnail, and the sender says so with the type on the intent.
        val asText = text != null && (intent.type ?: "").startsWith("text/")

        if (uris.isNotEmpty() && !asText) {
            // Off the main thread: a shared video can be hundreds of megabytes, and a provider
            // is free to hand back a pipe that never ends.
            Thread { copy(uris) }.start()
            return
        }
        if (text != null) {
            clipboard().setPrimaryClip(ClipData.newPlainText("shared", text))
            done("Copied. Ctrl+V to paste")
        } else {
            done(if (offered.isNotEmpty()) "That cannot be copied" else "Nothing to copy")
        }
    }

    /**
     * Only another app's content URI is read.
     *
     * A `file://` URI would be opened with Overkey's own uid, so any app could hand over a path
     * inside Overkey's private data, let this activity copy it into a grantable clip and then
     * read it straight back off the clipboard. The settings there hold the token the injector is
     * authenticated with, which is enough to drive it. Overkey's own providers are refused for
     * the same reason. Nothing legitimate is lost: sharing a `file://` URI has thrown on the
     * sender's side since Android 7.
     */
    private fun safe(u: Uri): Boolean {
        if (!ContentResolver.SCHEME_CONTENT.equals(u.scheme, ignoreCase = true)) return false
        val authority = u.authority ?: return false
        return authority != packageName && !authority.startsWith("$packageName.")
    }

    /**
     * Copies each URI into the cache under the sender's own name and type, then puts them on one
     * clip. The older clip files go only once the new ones are written, so a share that fails
     * leaves the last good clip where it was.
     */
    private fun copy(uris: List<Uri>) {
        val cr = contentResolver
        val kept = LinkedHashSet<String>()
        val mimes = LinkedHashSet<String>()
        val items = ArrayList<ClipData.Item>()
        for (u in uris) {
            val mime = try { cr.getType(u) } catch (_: Exception) { null }
            val display = displayName(u)
            val name = ClipProvider.name(ClipProvider.ext(display, mime))
            val f = ClipProvider.file(this, name)
            val ok = try {
                cr.openInputStream(u).use { input -> input != null && capped(input, f) }
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                f.delete() // A refusal or a half-written file is not a clip.
                continue
            }
            ClipProvider.note(this, name, mime, display)
            kept += name
            mimes += ClipProvider.mimeOf(this, name)
            items += ClipData.Item(ClipProvider.uri(this, name))
        }
        ClipProvider.sweep(this, kept)
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (items.isEmpty()) {
                done("Could not read that")
                return@runOnUiThread
            }
            val clip = ClipData("shared", mimes.toTypedArray(), items[0])
            for (i in 1 until items.size) clip.addItem(items[i])
            clipboard().setPrimaryClip(clip)
            done(if (items.size > 1) "${items.size} items copied. Ctrl+V to paste" else "Copied. Ctrl+V to paste")
        }
    }

    /** Copies at most [MAX_BYTES]; anything longer is refused rather than filling the cache. */
    private fun capped(input: InputStream, f: File): Boolean {
        var total = 0L
        val buf = ByteArray(64 * 1024)
        f.outputStream().use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) return false
                out.write(buf, 0, n)
            }
        }
        return true
    }

    /** What the sender calls the file, so the pasting app shows that and not "clip-1.zip". */
    private fun displayName(u: Uri): String? {
        return try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun clipboard() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun done(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
        else -> emptyList()
    }

    companion object {
        private const val MAX_BYTES = 256L * 1024 * 1024
    }
}
