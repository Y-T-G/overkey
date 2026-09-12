package dev.noblebits.overkey

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Serves the one picture copied to the clipboard, `content://<pkg>.clip/clip.png`, from the
 * cache directory. Not exported; the clipboard grants the pasting app a read on the URI
 * while it holds the clip. Hand-rolled rather than androidx FileProvider: the app has no
 * androidx, and this is all a paste needs, the bytes and a name and size for apps that ask.
 */
class ClipProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = if (uri.lastPathSegment == NAME) "image/png" else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uri.lastPathSegment != NAME || mode != "r") return null
        return ParcelFileDescriptor.open(file(context!!), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor? {
        if (uri.lastPathSegment != NAME) return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols, 1)
        val f = file(context!!)
        c.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> NAME
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
        const val NAME = "clip.png"
        fun file(c: android.content.Context) = File(c.cacheDir, NAME)
        fun uri(c: android.content.Context): Uri = Uri.parse("content://${c.packageName}.clip/$NAME")
    }
}
