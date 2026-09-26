package com.bluebeetle.whatsappfilebrowser

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile

class AsimFilesProvider : DocumentsProvider() {
    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES)
        return MatrixCursor(cols).apply {
            newRow().also { r ->
                cols.forEach { c -> when(c) {
                    Root.COLUMN_ROOT_ID -> r.add("asimfiles")
                    Root.COLUMN_DOCUMENT_ID -> r.add("home")
                    Root.COLUMN_TITLE -> r.add("asimFiles")
                    Root.COLUMN_FLAGS -> r.add(Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_LOCAL_ONLY)
                    Root.COLUMN_MIME_TYPES -> r.add("*/*")
                    else -> r.add(null)
                }}
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        return MatrixCursor(cols).apply { addDocumentRow(this, cols, documentId) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(cols)
        when (parentDocumentId) {
            "home" -> { addDocumentRow(cursor, cols, "downloads"); addDocumentRow(cursor, cols, "whatsapp"); addDocumentRow(cursor, cols, "others") }
            "downloads" -> scanDownloads(context!!).forEach { addItemRow(cursor, cols, "dl:" + it.uri.lastPathSegment, it) }
            "whatsapp" -> savedTree("root")?.let { scanFolder(context!!, it) }?.forEach { addItemRow(cursor, cols, "wa:" + android.util.Base64.encodeToString(it.uri.toString().toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP), it) }
            "others" -> savedTree("other_root")?.let { scanFolder(context!!, it) }?.forEach { addItemRow(cursor, cols, "ot:" + android.util.Base64.encodeToString(it.uri.toString().toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP), it) }
        }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val uri = when {
            documentId.startsWith("dl:") -> android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, documentId.removePrefix("dl:").toLong())
            documentId.startsWith("wa:") || documentId.startsWith("ot:") -> android.net.Uri.parse(String(android.util.Base64.decode(documentId.substringAfter(':'), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)))
            else -> throw java.io.FileNotFoundException(documentId)
        }
        return context!!.contentResolver.openFileDescriptor(uri, "r", signal) ?: throw java.io.FileNotFoundException(documentId)
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val out = MatrixCursor(cols)
        val q = query.lowercase()
        scanDownloads(context!!).filter { it.name.lowercase().contains(q) }.take(50).forEach { addItemRow(out, cols, "dl:" + it.uri.lastPathSegment, it) }
        savedTree("root")?.let { scanFolder(context!!, it) }?.filter { it.name.lowercase().contains(q) }?.take(50)?.forEach { addItemRow(out, cols, "wa:" + android.util.Base64.encodeToString(it.uri.toString().toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP), it) }
        return out
    }

    private fun savedTree(key: String) = context!!.getSharedPreferences("storage", 0).getString(key, null)?.let(android.net.Uri::parse)

    private fun addDocumentRow(cursor: MatrixCursor, cols: Array<out String>, id: String) {
        val title = when(id) { "home" -> "asimFiles"; "downloads" -> "Downloads"; "whatsapp" -> "WhatsApp Files"; else -> "Others" }
        addRow(cursor, cols, id, title, Document.MIME_TYPE_DIR, 0L, 0L)
    }
    private fun addItemRow(cursor: MatrixCursor, cols: Array<out String>, id: String, item: MediaItem) = addRow(cursor, cols, id, item.name, item.mime, item.size, item.added)
    private fun addRow(cursor: MatrixCursor, cols: Array<out String>, id: String, name: String, mime: String, size: Long, modified: Long) {
        val row = cursor.newRow()
        cols.forEach { c -> when(c) {
            Document.COLUMN_DOCUMENT_ID -> row.add(id)
            Document.COLUMN_DISPLAY_NAME -> row.add(name)
            Document.COLUMN_MIME_TYPE -> row.add(mime)
            Document.COLUMN_SIZE -> row.add(size)
            Document.COLUMN_LAST_MODIFIED -> row.add(modified)
            Document.COLUMN_FLAGS -> row.add(0)
            else -> row.add(null)
        }}
    }
    companion object { val DEFAULT_DOCUMENT_PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS) }
}
