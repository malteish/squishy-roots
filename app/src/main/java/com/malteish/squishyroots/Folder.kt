/*
 *     Squishy: der freigegebene Ordner (die Selektion), über SAF oder direkt als Datei.
 *
 *     Licensed under the GNU General Public License v3 or later, like Gramophone.
 */

package com.malteish.squishyroots

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Zugriff auf Dateien direkt im freigegebenen Ordner und auf `feedback/`. Normalerweise über SAF
 * ([Saf]). Wo es keinen Ordner-Dialog gibt (eingeschränktes Android-Profil), direkt über den Pfad
 * mit „Zugriff auf alle Dateien“ ([Direct]).
 */
sealed interface Folder {
    /** Inhalt der Datei [name], oder null, wenn sie fehlt. */
    fun read(name: String): ByteArray?

    /** Ersetzt den Inhalt der vorhandenen oder neuen Datei [name]. */
    fun write(name: String, data: ByteArray)

    /** Hängt [data] an `feedback/[name]` an, Ordner und Datei werden bei Bedarf angelegt. */
    fun appendFeedback(name: String, data: ByteArray)

    class Saf(private val resolver: ContentResolver, private val tree: Uri) : Folder {
        private val rootId get() = DocumentsContract.getTreeDocumentId(tree)

        override fun read(name: String): ByteArray? {
            val uri = find(rootId, name) ?: return null
            return resolver.openInputStream(uri)?.use { it.readBytes() }
        }

        override fun write(name: String, data: ByteArray) {
            // octet-stream, damit der Anbieter den Namen samt Endung unverändert lässt.
            val uri = findOrCreate(rootId, name, "application/octet-stream")
            resolver.openOutputStream(uri, "wt")!!.use { it.write(data) }
        }

        override fun appendFeedback(name: String, data: ByteArray) {
            val dir = findOrCreate(rootId, FEEDBACK_DIR, DocumentsContract.Document.MIME_TYPE_DIR)
            val file = findOrCreate(DocumentsContract.getDocumentId(dir), name, "application/octet-stream")
            resolver.openOutputStream(file, "wa")!!.use { it.write(data) }
        }

        private fun findOrCreate(parentId: String, name: String, mime: String): Uri {
            find(parentId, name)?.let { return it }
            return DocumentsContract.createDocument(resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, parentId), mime, name)
                ?: throw IllegalStateException("$name konnte nicht angelegt werden")
        }

        /** Die Datei [name] direkt unter [parentId], oder null. */
        private fun find(parentId: String, name: String): Uri? {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                while (it.moveToNext()) {
                    if (it.getString(1) == name)
                        return DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                }
            }
            return null
        }
    }

    class Direct(private val dir: File) : Folder {
        override fun read(name: String): ByteArray? =
            File(dir, name).takeIf { it.isFile }?.readBytes()

        override fun write(name: String, data: ByteArray) = File(dir, name).writeBytes(data)

        override fun appendFeedback(name: String, data: ByteArray) {
            val sub = File(dir, FEEDBACK_DIR)
            if (!sub.isDirectory && !sub.mkdirs())
                throw IllegalStateException("$FEEDBACK_DIR konnte nicht angelegt werden")
            File(sub, name).appendBytes(data)
        }
    }

    companion object {
        const val FEEDBACK_DIR = "feedback"
    }
}
