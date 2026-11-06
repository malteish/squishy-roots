/*
 *     Squishy Roots: Titel direkt zwischen M3U-Dateien und dem Papierkorb verschieben.
 *
 *     Licensed under the GNU General Public License v3 or later, like Gramophone.
 */

package com.malteish.squishyroots

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.text.Normalizer

/**
 * Bearbeitet die M3U-Dateien im freigegebenen Ordner (die Selektion, per Syncthing synchronisiert).
 * Eine Zeile samt vorangehender `#EXTINF`-Zeile gilt als ein Eintrag. Alles andere (Kopfzeilen,
 * `#DESCRIPTION:`, Leerzeilen) bleibt unverändert stehen. Im Papierkorb `<person>-abgelehnt` trägt
 * jeder Eintrag zusätzlich eine oder mehrere Zeilen `#SQUISHY-AUS:<liste>`, die neueste zuerst.
 * Andere Player übergehen sie als Kommentar. Die Pfade der Einträge sind relativ zum
 * Ordner der M3U, der Titel wird deshalb über das Pfadende erkannt.
 */
object PlaylistFiles {

    private class Block(val lines: List<String>, val track: String?)

    private class Doc(val uri: Uri, val blocks: List<Block>, val eol: String)

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

    /**
     * Verschiebt [itemPath] aus [from] in den Papierkorb [trash] und merkt sich [from] als
     * Herkunft (`#SQUISHY-AUS:`). Neue Einträge stehen oben. Steht der Titel schon im Papierkorb,
     * kommt nur die Herkunft dazu. Fehlt der Papierkorb, wird er mit [description] angelegt.
     */
    fun moveToTrash(
        resolver: ContentResolver, tree: Uri, from: String, trash: String, dir: String,
        itemPath: String, origin: String, description: String
    ): Boolean {
        val source = read(resolver, tree, from) ?: return false
        val moved = source.blocks.firstOrNull { matches(it, itemPath, dir) } ?: return false
        val target = read(resolver, tree, trash)
            ?: createEmpty(resolver, tree, trash, source.eol, description)
            ?: return false
        val originLine = ORIGIN_PREFIX + origin
        val existing = target.blocks.indexOfFirst { matches(it, itemPath, dir) }
        val blocks = target.blocks.toMutableList()
        if (existing >= 0) {
            val b = blocks.removeAt(existing)
            val lines = listOf(originLine) + b.lines.filterNot { it == originLine }
            blocks.add(firstTrack(blocks), Block(lines, b.track))
        } else {
            blocks.add(firstTrack(blocks),
                Block(listOf(originLine) + moved.lines.filterNot { it.startsWith(ORIGIN_PREFIX) },
                    moved.track))
        }
        write(resolver, target, blocks)
        write(resolver, source, source.blocks.filterNot { matches(it, itemPath, dir) })
        return true
    }

    /**
     * Holt [itemPath] aus dem Papierkorb [trash] zurück, soweit er aus [origin] kam: Er kommt
     * nach [to] (falls dort noch nicht vorhanden), und die Herkunft [origin] fällt weg. Ohne
     * weitere Herkunft verschwindet der Eintrag aus dem Papierkorb. false, wenn er dort nicht
     * mit dieser Herkunft stand oder [to] fehlt.
     */
    fun restore(
        resolver: ContentResolver, tree: Uri, trash: String, to: String, dir: String,
        itemPath: String, origin: String
    ): Boolean {
        val source = read(resolver, tree, trash) ?: return false
        val originLine = ORIGIN_PREFIX + origin
        val i = source.blocks.indexOfFirst { matches(it, itemPath, dir) && originLine in it.lines }
        if (i < 0) return false
        val b = source.blocks[i]
        val target = read(resolver, tree, to) ?: return false
        if (target.blocks.none { matches(it, itemPath, dir) }) {
            val entry = Block(b.lines.filterNot { it.startsWith(ORIGIN_PREFIX) }, b.track)
            write(resolver, target, target.blocks + entry)
        }
        val rest = b.lines.filterNot { it == originLine }
        val blocks = source.blocks.toMutableList()
        if (rest.any { it.startsWith(ORIGIN_PREFIX) }) blocks[i] = Block(rest, b.track)
        else blocks.removeAt(i)
        write(resolver, source, blocks)
        return true
    }

    /**
     * Leert die Liste [name]: Alle Einträge fallen weg, Kopfzeilen (`#EXTM3U`, `#DESCRIPTION:`)
     * bleiben. Gibt die Zahl der entfernten Einträge zurück.
     */
    fun clear(resolver: ContentResolver, tree: Uri, name: String): Int {
        val doc = read(resolver, tree, name) ?: return 0
        val head = doc.blocks.filter { it.track == null && it.lines.none { l -> l.startsWith("#EXTINF") ||
                l.startsWith(ORIGIN_PREFIX) || l.isBlank() } }
        val removed = doc.blocks.count { it.track != null }
        if (removed > 0) write(resolver, doc, head)
        return removed
    }

    /** Herkunft aller Einträge des Papierkorbs [file], neueste zuerst: Pfadzeile → Listen. */
    fun trashOrigins(file: File): List<Pair<String, List<String>>> = runCatching {
        parse(file.readText()).mapNotNull { b ->
            b.track?.let { t -> t to b.lines.filter { it.startsWith(ORIGIN_PREFIX) }
                .map { it.removePrefix(ORIGIN_PREFIX) } }
        }
    }.getOrDefault(emptyList())

    /** Ob die Pfadzeile [track] einer M3U in [dir] auf [itemPath] verweist. */
    fun matches(track: String, itemPath: String, dir: String): Boolean {
        val t = nfc(track.trim().removePrefix("./"))
        val p = nfc(itemPath)
        if (t.startsWith("/")) return t == p
        return p == nfc(dir).trimEnd('/') + "/" + t || p.endsWith("/$t")
    }

    private fun firstTrack(blocks: List<Block>) =
        blocks.indexOfFirst { it.track != null }.let { if (it < 0) blocks.size else it }

    const val ORIGIN_PREFIX = "#SQUISHY-AUS:"

    private fun matches(block: Block, itemPath: String, dir: String): Boolean =
        block.track?.let { matches(it, itemPath, dir) } == true

    /**
     * Legt eine leere M3U [name] direkt im freigegebenen Ordner an. Als Typ octet-stream, damit
     * der Anbieter den Namen samt Endung unverändert lässt.
     */
    private fun createEmpty(
        resolver: ContentResolver, tree: Uri, name: String, eol: String, description: String? = null
    ): Doc? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree,
            DocumentsContract.getTreeDocumentId(tree))
        val uri = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            ?: return null
        val head = listOfNotNull("#EXTM3U", description?.let { "#DESCRIPTION:$it" })
        val doc = Doc(uri, head.map { Block(listOf(it), null) }, eol)
        write(resolver, doc, doc.blocks)
        return doc
    }

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun read(resolver: ContentResolver, tree: Uri, name: String): Doc? {
        val uri = find(resolver, tree, DocumentsContract.getTreeDocumentId(tree), name) ?: return null
        val text = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        val eol = if ("\r\n" in text) "\r\n" else "\n"
        return Doc(uri, parse(text), eol)
    }

    private fun parse(text: String): List<Block> {
        val lines = text.split(Regex("\r?\n")).let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        val blocks = mutableListOf<Block>()
        var pending = mutableListOf<String>()
        for (line in lines) {
            when {
                line.startsWith("#EXTINF") || line.startsWith(ORIGIN_PREFIX) -> pending.add(line)
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
        return blocks
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
