/*
 *     Squishy Roots: Titel direkt aus M3U-Dateien nehmen oder dorthin verschieben.
 *
 *     Licensed under the GNU General Public License v3 or later, like Gramophone.
 */

package com.malteish.squishyroots

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.text.Normalizer

/**
 * Bearbeitet die M3U-Dateien im freigegebenen Ordner (die Selektion, per Syncthing synchronisiert).
 * Eine Zeile samt vorangehender `#EXTINF`-Zeile gilt als ein Eintrag. Alles andere (Kopfzeilen,
 * `#DESCRIPTION:`, Leerzeilen) bleibt unverändert stehen. Die Pfade der Einträge sind relativ zum
 * Ordner der M3U, der Titel wird deshalb über das Pfadende erkannt.
 */
object PlaylistFiles {

    private class Block(val lines: List<String>, val track: String?)

    private class Doc(val uri: Uri, val blocks: List<Block>, val eol: String)

    /** Nimmt [itemPath] aus der Liste [name]. true, wenn er darin stand. */
    fun remove(resolver: ContentResolver, tree: Uri, name: String, dir: String, itemPath: String): Boolean {
        val doc = read(resolver, tree, name) ?: return false
        val rest = doc.blocks.filterNot { matches(it, itemPath, dir) }
        if (rest.size == doc.blocks.size) return false
        write(resolver, doc, rest)
        return true
    }

    /**
     * Verschiebt [itemPath] von der Liste [from] in die Liste [to], falls dort noch nicht
     * vorhanden. Zuerst wird [to] geschrieben, damit bei einem Fehler nichts verloren geht.
     * false, wenn [to] fehlt oder der Titel nicht in [from] stand.
     */
    fun move(
        resolver: ContentResolver, tree: Uri, from: String, to: String, dir: String, itemPath: String
    ): Boolean {
        val source = read(resolver, tree, from) ?: return false
        val target = read(resolver, tree, to) ?: return false
        val moved = source.blocks.filter { matches(it, itemPath, dir) }
        if (moved.isEmpty()) return false
        if (target.blocks.none { matches(it, itemPath, dir) })
            write(resolver, target, target.blocks + moved.take(1))
        write(resolver, source, source.blocks.filterNot { matches(it, itemPath, dir) })
        return true
    }

    private fun matches(block: Block, itemPath: String, dir: String): Boolean {
        val track = block.track ?: return false
        val t = nfc(track.trim().removePrefix("./"))
        val p = nfc(itemPath)
        if (t.startsWith("/")) return t == p
        return p == nfc(dir).trimEnd('/') + "/" + t || p.endsWith("/$t")
    }

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun read(resolver: ContentResolver, tree: Uri, name: String): Doc? {
        val uri = find(resolver, tree, DocumentsContract.getTreeDocumentId(tree), name) ?: return null
        val text = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        val eol = if ("\r\n" in text) "\r\n" else "\n"
        val lines = text.split(Regex("\r?\n")).let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        val blocks = mutableListOf<Block>()
        var pending = mutableListOf<String>()
        for (line in lines) {
            when {
                line.startsWith("#EXTINF") -> pending.add(line)
                line.isBlank() || line.startsWith("#") -> {
                    pending.forEach { blocks.add(Block(listOf(it), null)) }
                    pending = mutableListOf()
                    blocks.add(Block(listOf(line), null))
                }
                else -> {
                    blocks.add(Block(pending + line, line))
                    pending = mutableListOf()
                }
            }
        }
        pending.forEach { blocks.add(Block(listOf(it), null)) }
        return Doc(uri, blocks, eol)
    }

    private fun write(resolver: ContentResolver, doc: Doc, blocks: List<Block>) {
        val text = blocks.flatMap { it.lines }.joinToString(doc.eol, postfix = doc.eol)
        resolver.openOutputStream(doc.uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Die Datei [name] direkt unter [parentId], oder null. */
    fun find(resolver: ContentResolver, tree: Uri, parentId: String, name: String): Uri? {
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
