package dev.noblebits.overkey

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves whatever is on Overkey's clipboard, `content://<pkg>.clip/clip-<n>.<ext>`, from the
 * cache directory. Not exported; the clipboard grants the pasting app a read on the URI while
 * it holds the clip. Hand-rolled rather than androidx FileProvider: the app has no androidx,
 * and this is all a paste needs, the bytes and a name and size for apps that ask.
 *
 * Two things put files here: aim mode's Copy image (a PNG) and a share into the app
 * ([ShareActivity], any of the media types below). Each copy clears the older files and takes
 * a fresh name, so a paste in progress is never torn. The served type comes from the file's
 * extension, which is fixed to one of [EXT_MIME]; anything else is refused, so the provider
 * can only ever hand out a known media type from its own cache directory.
 */
class ClipProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = if (valid(uri)) mimeOf(uri.lastPathSegment!!) else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (!valid(uri) || mode != "r") return null
        return ParcelFileDescriptor.open(file(context!!, uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor? {
        if (!valid(uri)) return null
        val name = uri.lastPathSegment!!
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols, 1)
        val f = file(context!!, name)
        c.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        })
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

    companion object {
        private val NAME = Regex("clip-\\d+\\.[a-z0-9]{1,5}")
        private val counter = AtomicLong(0)

        // The only extensions the provider will serve, each with the type it is served as.
        // A shared file whose type is not one of these is stored as ".bin" and paste is left
        // to the receiving app, which is what "media that can be clipboarded" already means.
        private val EXT_MIME = linkedMapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic",
            "mp4" to "video/mp4", "webm" to "video/webm", "3gp" to "video/3gpp", "mkv" to "video/x-matroska",
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "ogg" to "audio/ogg", "wav" to "audio/wav", "opus" to "audio/opus",
            "pdf" to "application/pdf", "txt" to "text/plain",
            "bin" to "application/octet-stream"
        )

        private fun mimeOf(name: String) = EXT_MIME[name.substringAfterLast('.')] ?: "application/octet-stream"

        /** One path segment of the served shape, with a known extension; anything else is refused. */
        private fun valid(uri: Uri): Boolean {
            val name = uri.lastPathSegment ?: return false
            return uri.pathSegments.size == 1 && NAME.matches(name) && EXT_MIME.containsKey(name.substringAfterLast('.'))
        }

        fun file(c: Context, name: String) = File(c.cacheDir, name)
        fun uri(c: Context, name: String): Uri = Uri.parse("content://${c.packageName}.clip/$name")

        /** The extension to store a file of type [mime] under, so it is served back as that type. */
        fun ext(mime: String?): String = EXT_MIME.entries.firstOrNull { it.value == mime }?.key ?: "bin"

        /** Deletes every clip file. Called before the first name of a new copy. */
        fun clear(c: Context) {
            c.cacheDir.listFiles()?.forEach { if (NAME.matches(it.name)) it.delete() }
        }

        /** A unique name of the given type, without clearing; for the later items of one copy. */
        fun name(ext: String) = "clip-${SystemClock.elapsedRealtime() * 1000 + (counter.incrementAndGet() % 1000)}.$ext"

        /** A name for the next copy; the earlier copies are deleted first. */
        fun fresh(c: Context, ext: String = "png"): String {
            clear(c)
            return name(ext)
        }
    }
}
