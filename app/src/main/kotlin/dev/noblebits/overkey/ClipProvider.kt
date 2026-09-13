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
 * and this is all a paste needs, the bytes and a name and type for apps that ask.
 *
 * Two things put files here: aim mode's Copy image (a PNG) and a share into the app
 * ([ShareActivity], anything at all). The file on disk is always named `clip-<digits>.<ext>`,
 * which is what makes the path safe to serve; the type and the name the pasting app is told
 * are kept beside it in [NOTES] instead of being guessed from the extension. That matters:
 * a zip handed out as `clip-1.bin` of type `application/octet-stream` is refused by the app
 * pasting it, while the same bytes as `holiday.zip` of type `application/zip` are taken.
 */
class ClipProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = if (valid(uri)) mimeOf(context!!, uri.lastPathSegment!!) else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (!valid(uri) || mode != "r") return null
        val f = file(context!!, uri.lastPathSegment!!)
        if (!f.isFile) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor? {
        if (!valid(uri)) return null
        val name = uri.lastPathSegment!!
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols, 1)
        val f = file(context!!, name)
        // A name that is no longer on disk is not a row: a caller that checks before opening
        // should see nothing rather than a file of length zero.
        if (!f.isFile) return c
        c.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> displayOf(context!!, name)
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
        // The only shape served. Matched whole, on a single decoded path segment, so no name
        // can climb out of the cache directory.
        private val NAME = Regex("clip-\\d+\\.[A-Za-z0-9]{1,8}")
        private val EXT = Regex("[A-Za-z0-9]{1,8}")
        // "type/subtype", the characters RFC 2045 allows in a token.
        private val MIME = Regex("[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,64}")
        private const val NOTES = "clips"
        private const val FALLBACK = "application/octet-stream"
        private val counter = AtomicLong(0)

        /** A guess at the extension only, for the name on disk; the type served is [note]d. */
        private val EXT_OF_MIME = mapOf(
            "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif",
            "image/webp" to "webp", "image/bmp" to "bmp", "image/heic" to "heic",
            "video/mp4" to "mp4", "video/webm" to "webm", "video/3gpp" to "3gp",
            "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/ogg" to "ogg",
            "audio/wav" to "wav", "application/pdf" to "pdf", "application/zip" to "zip",
            "text/plain" to "txt",
        )

        private fun notes(c: Context) = c.getSharedPreferences(NOTES, Context.MODE_PRIVATE)

        /** One path segment of the served shape; anything else, traversal included, is refused. */
        private fun valid(uri: Uri): Boolean {
            val name = uri.lastPathSegment ?: return false
            return uri.pathSegments.size == 1 && NAME.matches(name)
        }

        fun file(c: Context, name: String) = File(c.cacheDir, name)
        fun uri(c: Context, name: String): Uri = Uri.parse("content://${c.packageName}.clip/$name")

        /**
         * The extension to store a file under. The name the sender gave it is the best source,
         * because it is what the sender's own app chose; the type is the fallback.
         */
        fun ext(display: String?, mime: String?): String {
            val fromName = display?.substringAfterLast('.', "")?.lowercase()
            if (fromName != null && fromName.isNotEmpty() && EXT.matches(fromName)) return fromName
            return EXT_OF_MIME[mime?.lowercase()] ?: "bin"
        }

        /** A unique name of the given type. Unique across a boot, and within one copy. */
        fun name(ext: String) = "clip-${SystemClock.elapsedRealtime() * 1000 + (counter.incrementAndGet() % 1000)}.$ext"

        /**
         * Records what the pasting app should be told about [name]: the type the sender gave it
         * and the name the sender called it. Without this a paste gets the extension's guess,
         * which for anything outside the table above is "a nameless blob of bytes".
         */
        fun note(c: Context, name: String, mime: String?, display: String?) {
            val type = if (mime != null && MIME.matches(mime)) mime else FALLBACK
            // Only the last segment, and nothing that could be read as a path.
            val shown = display?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.filter { it.code >= 0x20 }?.take(120)?.ifBlank { null } ?: name
            notes(c).edit().putString("$name.mime", type).putString("$name.name", shown).apply()
        }

        fun mimeOf(c: Context, name: String): String = notes(c).getString("$name.mime", null) ?: FALLBACK

        fun displayOf(c: Context, name: String): String = notes(c).getString("$name.name", null) ?: name

        /**
         * Deletes every clip file except [keep], and the notes that went with them. Called once
         * the replacements are on disk, so a copy that fails leaves the last good clip pasteable.
         */
        fun sweep(c: Context, keep: Set<String>) {
            val e = notes(c).edit()
            c.cacheDir.listFiles()?.forEach {
                if (NAME.matches(it.name) && it.name !in keep) {
                    it.delete()
                    e.remove("${it.name}.mime").remove("${it.name}.name")
                }
            }
            e.apply()
        }
    }
}
