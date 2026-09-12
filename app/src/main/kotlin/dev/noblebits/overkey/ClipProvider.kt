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

/**
 * Serves the picture copied to the clipboard, `content://<pkg>.clip/clip-<n>.png`, from the
 * cache directory. Not exported; the clipboard grants the pasting app a read on the URI
 * while it holds the clip. Hand-rolled rather than androidx FileProvider: the app has no
 * androidx, and this is all a paste needs, the bytes and a name and size for apps that ask.
 * Each copy gets a new name and the older files go, so a paste in progress is never torn.
 */
class ClipProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = if (valid(uri)) "image/png" else null

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
        private val NAME = Regex("clip-\\d+\\.png")

        /** One path segment of the served shape; anything else, traversal included, is refused. */
        private fun valid(uri: Uri) = uri.pathSegments.size == 1 && NAME.matches(uri.lastPathSegment ?: "")

        fun file(c: Context, name: String) = File(c.cacheDir, name)
        fun uri(c: Context, name: String): Uri = Uri.parse("content://${c.packageName}.clip/$name")

        /** A name for the next copy; the earlier copies are deleted. */
        fun fresh(c: Context): String {
            c.cacheDir.listFiles()?.forEach { if (NAME.matches(it.name)) it.delete() }
            return "clip-${SystemClock.elapsedRealtime()}.png"
        }
    }
}
