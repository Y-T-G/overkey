package dev.noblebits.overkey

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * The share target. Send anything to Overkey from another app's share sheet and it goes on the
 * clipboard, so the next Ctrl+V pastes it, in apps that offer no copy of their own.
 *
 * Text goes on as plain text. A file, picture, video or anything else comes in as a URI the
 * sender granted this app a read on; that grant does not reach the app that later pastes, so
 * the bytes are copied into the cache and served from [ClipProvider], whose URI the clipboard
 * can grant on. The clip carries the file's own type, so the pasting app sees a picture as a
 * picture and a PDF as a PDF. Whether a paste lands is the receiving app's call: Android has no
 * general "paste a file into a folder" the way a desktop does, but an image field, a chat box
 * or an editor takes what it understands.
 *
 * No window of its own: it reads the intent, writes the clipboard, says so and finishes.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            toast(handle(intent))
        } catch (_: Exception) {
            toast("Could not copy that")
        }
        finish()
    }

    /** Puts the shared content on the clipboard and returns the line to show. */
    private fun handle(intent: Intent): String {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> stream(intent)?.let { listOf(it) } ?: emptyList()
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            else -> emptyList()
        }
        return when {
            uris.isNotEmpty() -> copyUris(uris)
            // A URL or note shared as text: EXTRA_TEXT, no stream.
            text != null -> {
                clipboard().setPrimaryClip(ClipData.newPlainText("shared", text))
                "Copied. Ctrl+V to paste"
            }
            else -> "Nothing to copy"
        }
    }

    /**
     * Each URI is copied into the cache under its own type and put on one clip, the first item
     * with the rest added to it. The older clip files are cleared once, before the first copy,
     * so a paste still reading the last share is not torn.
     */
    private fun copyUris(uris: List<Uri>): String {
        ClipProvider.clear(this)
        val cr = contentResolver
        var clip: ClipData? = null
        var n = 0
        for (u in uris) {
            val mime = cr.getType(u)
            val name = ClipProvider.name(ClipProvider.ext(mime))
            try {
                cr.openInputStream(u).use { input ->
                    if (input == null) return@use
                    ClipProvider.file(this, name).outputStream().use { input.copyTo(it) }
                }
            } catch (_: Exception) {
                continue
            }
            val item = ClipData.Item(ClipProvider.uri(this, name))
            if (clip == null) {
                clip = ClipData("shared", arrayOf(mime ?: "application/octet-stream"), item)
            } else {
                clip.addItem(item)
            }
            n++
        }
        if (clip == null) return "Could not read that"
        clipboard().setPrimaryClip(clip)
        return if (n > 1) "$n items copied. Ctrl+V to paste" else "Copied. Ctrl+V to paste"
    }

    private fun clipboard() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
}
