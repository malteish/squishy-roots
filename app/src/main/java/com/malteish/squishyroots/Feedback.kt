/*
 *     Squishy Roots: Feedback mit Kontext (Daumen, Gründe, Notizen).
 *
 *     Licensed under the GNU General Public License v3 or later, like Gramophone.
 */

package com.malteish.squishyroots

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.BuildConfig
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.getFile
import org.akanework.gramophone.ui.MainActivity
import org.json.JSONObject
import uk.akane.libphonograph.items.Playlist
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Schreibt jeden Knopfdruck als eine JSON-Zeile an `feedback/<person>.jsonl` im freigegebenen
 * Ordner (die per Syncthing synchronisierte Selektion). Die Datei wird nur angehängt.
 *
 * Kontext eines Ereignisses: Playlist-Name und -Beschreibung (aus der Kopfzeile `#DESCRIPTION:`
 * der M3U), Person und Zeit. Die Playlist ist die Warteschlange, aus der zuletzt gestartet wurde,
 * und zählt nur, wenn der laufende Titel in ihr steht.
 */
object Feedback {
    const val JA = "ja"
    const val NEIN = "nein"
    const val SPAETER = "spaeter"

    private const val KEY_PERSON = "squishy_person"
    private const val KEY_TREE = "squishy_tree"
    private const val KEY_QUEUE = "squishy_queue"
    private const val DESCRIPTION_PREFIX = "#DESCRIPTION:"

    private var prefs: SharedPreferences? = null
    private var queueTitle: String? = null
    private var picker: ActivityResultLauncher<Uri?>? = null
    private var afterPick: (() -> Unit)? = null

    /** Aus [MainActivity.onCreate] aufrufen, bevor die Activity startet. */
    fun register(activity: MainActivity) {
        init(activity)
        picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs!!.edit { putString(KEY_TREE, uri.toString()) }
                afterPick?.invoke()
            }
            afterPick = null
        }
    }

    private fun init(context: Context) {
        if (prefs == null) {
            prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            queueTitle = prefs!!.getString(KEY_QUEUE, null)
        }
    }

    /** Aufgerufen, wenn die UI eine neue Warteschlange mit Titel startet. */
    fun rememberQueue(title: String) {
        queueTitle = title
        prefs?.edit { putString(KEY_QUEUE, title) }
    }

    /** Letztes Urteil zu diesem Titel in der aktuellen Warteschlange (für die Anzeige). */
    fun currentVerdict(item: MediaItem?): String? {
        val key = verdictKey(item) ?: return null
        return prefs?.getString(key, null)
    }

    private fun verdictKey(item: MediaItem?): String? {
        if (item == null) return null
        return "squishy_v:" + (queueTitle ?: "") + "|" + item.mediaId
    }

    fun onThumb(activity: MainActivity, item: MediaItem?, urteil: String, onDone: () -> Unit) {
        if (item == null) return
        val grund = if (urteil == NEIN) "passt_nicht" else null
        // Erneutes Tippen auf den aktiven Daumen nimmt das Urteil zurück.
        if (currentVerdict(item) == urteil)
            record(activity, item, null, "zurueckgenommen", null, onDone)
        else
            record(activity, item, urteil, grund, null, onDone)
    }

    fun showMenu(activity: MainActivity, anchor: View, item: MediaItem?, onDone: () -> Unit) {
        if (item == null) return
        val menu = PopupMenu(activity, anchor)
        menu.menuInflater.inflate(R.menu.squishy_feedback_menu, menu.menu)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.squishy_passt_nicht -> record(activity, item, NEIN, "passt_nicht", null, onDone)
                R.id.squishy_mag_nicht -> record(activity, item, NEIN, "mag_nicht", null, onDone)
                R.id.squishy_falsche_version -> record(activity, item, NEIN, "falsche_version", null, onDone)
                R.id.squishy_defekt -> record(activity, item, NEIN, "defekt", null, onDone)
                R.id.squishy_spaeter -> record(activity, item, SPAETER, null, null, onDone)
                R.id.squishy_notiz -> askText(activity, R.string.squishy_notiz, null) { text ->
                    record(activity, item, null, null, text, onDone)
                }
                R.id.squishy_einrichten -> setup(activity, force = true) {}
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        menu.show()
    }

    private fun record(
        activity: MainActivity, item: MediaItem, urteil: String?, grund: String?,
        notiz: String?, onDone: () -> Unit
    ) {
        init(activity)
        setup(activity, force = false) {
            activity.lifecycleScope.launch {
                val event = buildEvent(activity, item, urteil, grund, notiz)
                val error = withContext(Dispatchers.IO) {
                    runCatching { append(activity.contentResolver, event) }.exceptionOrNull()
                }
                if (error != null) {
                    Toast.makeText(activity, activity.getString(R.string.squishy_fehler,
                        error.message), Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (notiz == null) {
                    prefs!!.edit {
                        if (urteil == null) remove(verdictKey(item))
                        else putString(verdictKey(item), urteil)
                    }
                }
                val ziel = event.optString("playlist").ifEmpty {
                    activity.getString(R.string.squishy_ohne_playlist)
                }
                Toast.makeText(activity, activity.getString(R.string.squishy_gespeichert,
                    label(activity, urteil, grund, notiz), ziel), Toast.LENGTH_SHORT).show()
                onDone()
            }
        }
    }

    private fun label(context: Context, urteil: String?, grund: String?, notiz: String?): String =
        context.getString(when {
            notiz != null -> R.string.squishy_notiz
            grund == "zurueckgenommen" -> R.string.squishy_zurueckgenommen
            urteil == JA -> R.string.squishy_ja
            urteil == SPAETER -> R.string.squishy_spaeter
            grund == "mag_nicht" -> R.string.squishy_mag_nicht
            grund == "falsche_version" -> R.string.squishy_falsche_version
            grund == "defekt" -> R.string.squishy_defekt
            else -> R.string.squishy_passt_nicht
        })

    private suspend fun buildEvent(
        activity: MainActivity, item: MediaItem, urteil: String?, grund: String?, notiz: String?
    ): JSONObject {
        val queue = queueTitle
        val playlist = if (queue == null) null else activity.reader.playlistListFlow.first()
            .firstOrNull { it.javaClass == Playlist::class.java && it.title == queue }
            ?.takeIf { pl -> pl.songList.any { it.mediaId == item.mediaId } }
        val beschreibung = playlist?.path?.let { withContext(Dispatchers.IO) { readDescription(it) } }
        val meta = item.mediaMetadata
        return JSONObject().apply {
            put("zeit", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("person", prefs!!.getString(KEY_PERSON, null))
            put("playlist", playlist?.title ?: JSONObject.NULL)
            put("beschreibung", beschreibung ?: JSONObject.NULL)
            put("warteschlange", queue ?: JSONObject.NULL)
            put("pfad", item.getFile()?.path ?: JSONObject.NULL)
            put("interpret", meta.artist?.toString() ?: JSONObject.NULL)
            put("titel", meta.title?.toString() ?: JSONObject.NULL)
            put("album", meta.albumTitle?.toString() ?: JSONObject.NULL)
            put("urteil", urteil ?: JSONObject.NULL)
            put("grund", grund ?: JSONObject.NULL)
            put("notiz", notiz ?: JSONObject.NULL)
            put("quelle", "knopf")
            put("app", BuildConfig.VERSION_NAME)
        }
    }

    private fun readDescription(file: File): String? = runCatching {
        file.useLines { lines ->
            lines.take(20).firstOrNull { it.startsWith(DESCRIPTION_PREFIX) }
                ?.removePrefix(DESCRIPTION_PREFIX)?.trim()?.ifEmpty { null }
        }
    }.getOrNull()

    private fun append(resolver: ContentResolver, event: JSONObject) {
        val tree = Uri.parse(prefs!!.getString(KEY_TREE, null)!!)
        val person = prefs!!.getString(KEY_PERSON, null)!!
        val dir = findOrCreate(resolver, tree, DocumentsContract.getTreeDocumentId(tree),
            "feedback", DocumentsContract.Document.MIME_TYPE_DIR)
        val file = findOrCreate(resolver, tree, DocumentsContract.getDocumentId(dir),
            "$person.jsonl", "application/octet-stream")
        resolver.openOutputStream(file, "wa")!!.use {
            it.write((event.toString() + "\n").toByteArray())
        }
    }

    private fun findOrCreate(
        resolver: ContentResolver, tree: Uri, parentId: String, name: String, mime: String
    ): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            while (it.moveToNext()) {
                if (it.getString(1) == name)
                    return DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
            }
        }
        return DocumentsContract.createDocument(resolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, parentId), mime, name)
            ?: throw IllegalStateException("$name konnte nicht angelegt werden")
    }

    /** Fragt Person und Ordner ab, falls noch nicht eingerichtet (oder [force]). */
    private fun setup(activity: MainActivity, force: Boolean, then: () -> Unit) {
        init(activity)
        val p = prefs!!
        val treeOk = p.getString(KEY_TREE, null)?.let { tree ->
            activity.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == tree && it.isWritePermission
            }
        } == true
        when {
            force || p.getString(KEY_PERSON, null) == null ->
                askText(activity, R.string.squishy_person, p.getString(KEY_PERSON, null)) { name ->
                    p.edit { putString(KEY_PERSON, name.trim().lowercase()) }
                    if (force) pickTree(activity, then) else setup(activity, false, then)
                }
            !treeOk -> pickTree(activity, then)
            else -> then()
        }
    }

    private fun pickTree(activity: MainActivity, then: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.squishy_ordner)
            .setMessage(R.string.squishy_ordner_hinweis)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                afterPick = then
                picker!!.launch(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askText(activity: MainActivity, title: Int, preset: String?, onOk: (String) -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(preset ?: "")
        }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val container = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().takeIf { it.isNotBlank() }?.let(onOk)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        input.requestFocus()
    }
}
