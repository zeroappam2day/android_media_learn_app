package com.example

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only SAF failure fixture. Disabled in the manifest except during guarded instrumentation. */
class Qa71DocumentsProvider : DocumentsProvider() {
    companion object {
        @Volatile private var ownedToken: String? = null
        @Volatile private var refuseWrites = true
        val refusedOpenCount = AtomicInteger(0)
        private val tokenPattern = Regex("[A-Za-z0-9]{8,80}")
        private fun guard(context: Context) {
            check(context.packageName.endsWith(".qa.preview"))
            check(Build.VERSION.SDK_INT == 36)
            check(Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
                Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        }
        fun beginOwnedFixture(context: Context, token: String) {
            guard(context); check(tokenPattern.matches(token)); check(ownedToken == null)
            ownedToken = token; refuseWrites = true; refusedOpenCount.set(0)
            File(context.cacheDir, "qa71-documents/$token").mkdirs()
        }
        fun endOwnedFixture(context: Context, token: String) {
            guard(context); check(ownedToken == token)
            val root = File(context.cacheDir, "qa71-documents/$token")
            check(root.canonicalFile.parentFile == File(context.cacheDir, "qa71-documents").canonicalFile)
            root.deleteRecursively(); ownedToken = null; refuseWrites = true
        }
    }
    override fun onCreate(): Boolean = context?.packageName?.endsWith(".qa.preview") == true
    private fun token(): String {
        val ctx = requireNotNull(context); guard(ctx)
        return ownedToken ?: throw FileNotFoundException("No active QA fixture")
    }
    private fun rootId() = "${token()}:root"
    private fun file(id: String): File {
        val t = token(); val prefix = "$t:file:"
        if (!id.startsWith(prefix)) throw FileNotFoundException("Not an owned file")
        val name = id.removePrefix(prefix)
        if (!Regex("[A-Za-z0-9_.-]+\\.json").matches(name) || !name.startsWith(t) || name.contains("..")) throw FileNotFoundException("Invalid owned filename")
        val root = File(requireNotNull(context).cacheDir, "qa71-documents/$t").canonicalFile
        return File(root, name).also { if (it.canonicalFile.parentFile != root) throw FileNotFoundException("Outside fixture") }
    }
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES)
        val cursor = MatrixCursor(cols)
        if (ownedToken == null) return cursor
        val t = token()
        val row = mapOf<String, Any>(Root.COLUMN_ROOT_ID to t, Root.COLUMN_DOCUMENT_ID to rootId(), Root.COLUMN_TITLE to "QA71 Refusing $t", Root.COLUMN_FLAGS to Root.FLAG_SUPPORTS_CREATE, Root.COLUMN_MIME_TYPES to "application/json", Root.COLUMN_AVAILABLE_BYTES to 1048576L)
        cursor.addRow(cols.map { row[it] }.toTypedArray()); return cursor
    }
    private fun documentCursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE))
    private fun addDocument(cursor: MatrixCursor, id: String) {
        val isRoot = id == rootId(); val f = if (isRoot) null else file(id)
        if (!isRoot && !requireNotNull(f).exists()) throw FileNotFoundException("Missing owned file")
        val row = mapOf<String, Any>(Document.COLUMN_DOCUMENT_ID to id, Document.COLUMN_DISPLAY_NAME to (if (isRoot) "QA71 Refusing ${token()}" else f!!.name), Document.COLUMN_MIME_TYPE to (if (isRoot) Document.MIME_TYPE_DIR else "application/json"), Document.COLUMN_FLAGS to (if (isRoot) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE), Document.COLUMN_SIZE to (f?.length() ?: 0L))
        cursor.addRow(cursor.columnNames.map { row[it] }.toTypedArray())
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = documentCursor(projection).also { addDocument(it, documentId) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (parentDocumentId != rootId()) throw FileNotFoundException("Invalid owned root")
        return documentCursor(projection).also { cursor -> File(requireNotNull(context).cacheDir, "qa71-documents/${token()}").listFiles().orEmpty().sortedBy { it.name }.forEach { addDocument(cursor, "${token()}:file:${it.name}") } }
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (parentDocumentId != rootId() || mimeType != "application/json") throw FileNotFoundException("Invalid owned create")
        val id = "${token()}:file:$displayName"; val f = file(id)
        if (!f.createNewFile()) throw FileNotFoundException("Owned filename already exists")
        return id
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = file(documentId)
        if (mode.contains('w') && refuseWrites) {
            refusedOpenCount.incrementAndGet()
            throw FileNotFoundException("QA71 owned destination refuses writes")
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }
}
