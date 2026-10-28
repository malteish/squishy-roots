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
import com.google.android.material.snackbar.Snackbar
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
 * Kontext eines Ereignisses: Situation (Playlist-Name), Beschreibung (aus der Kopfzeile
 * `#DESCRIPTION:` der M3U), Person und Zeit. Eine Liste `<name>-kandidaten` gehört zur Situation
 * `<name>`. Im Player ist die Liste die Warteschlange, aus der zuletzt gestartet wurde, beim
 * Wischen die angezeigte Playlist. Sie zählt nur, wenn der Titel in ihr steht.
 */
object Feedback {
    const val JA = "ja"
    const val NEIN = "nein"
    const val SPAETER = "spaeter"

    private const val KEY_PERSON = "squishy_person"
    private const val KEY_TREE = "squishy_tree"
    private const val KEY_QUEUE = "squishy_queue"
    private const val DESCRIPTION_PREFIX = "#DESCRIPTION:"
    const val CANDIDATE_SUFFIX = "-kandidaten"

    /** Situation einer Liste: `x-kandidaten` gehört zu `x`. */
    fun situationOf(listTitle: String?): String? = listTitle?.removeSuffix(CANDIDATE_SUFFIX)

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

    /** Die schon geladenen Standard-Einstellungen (kein erneuter Plattenzugriff). */
    fun preferences(context: Context): SharedPreferences {
        init(context)
        return prefs!!
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

    /**
     * Bewertung als Zahl: +1 bis +3 (passt hierher, wie stark), −1 bis −3 (passt nicht), 0 keine.
     * Jedes weitere Tippen oder Wischen in dieselbe Richtung verstärkt bis 3. Die Gegenrichtung
     * beginnt wieder bei 1.
     */
    const val MAX_STRENGTH = 3

    fun step(current: Int, up: Boolean): Int =
        if (up) (if (current <= 0) 1 else minOf(current + 1, MAX_STRENGTH))
        else (if (current >= 0) -1 else maxOf(current - 1, -MAX_STRENGTH))

    /** Bewertung des laufenden Titels in der Situation der Warteschlange (für den Player). */
    fun currentScore(item: MediaItem?): Int =
        if (item == null) 0 else score(situationOf(queueTitle), item.mediaId)

    /** Bewertung eines Titels in einer Situation (für die Markierung in Listen). */
    fun score(situation: String?, mediaId: String): Int {
        val p = prefs ?: return 0
        val key = scoreKey(situation, mediaId)
        if (p.contains(key)) return p.getInt(key, 0)
        // Bis v0.3.0 als Text ja/nein gespeichert.
        return when (p.getString(VERDICT_PREFIX + (situation ?: "") + "|" + mediaId, null)) {
            JA -> 1
            NEIN -> -1
            else -> 0
        }
    }

    const val VERDICT_PREFIX = "squishy_v:"
    const val SCORE_PREFIX = "squishy_s:"

    private fun scoreKey(situation: String?, mediaId: String) =
        SCORE_PREFIX + (situation ?: "") + "|" + mediaId

    fun onThumb(activity: MainActivity, item: MediaItem?, up: Boolean, onDone: () -> Unit) {
        if (item == null) return
        val next = step(currentScore(item), up)
        record(activity, item, queueTitle, next, if (next < 0) "passt_nicht" else null, null,
            "knopf", onDone)
    }

    /** Wischen in einer Playlist: [listTitle] ist die angezeigte Liste. */
    fun onSwipe(
        activity: MainActivity, item: MediaItem, listTitle: String, up: Boolean,
        onDone: () -> Unit
    ) {
        val situation = situationOf(listTitle)
        val next = step(score(situation, item.mediaId), up)
        record(activity, item, listTitle, next, if (next < 0) "passt_nicht" else null, null,
            "wischen", onDone, undo = true)
    }

    fun showMenu(activity: MainActivity, anchor: View, item: MediaItem?, onDone: () -> Unit) {
        if (item == null) return
        val menu = PopupMenu(activity, anchor)
        menu.menuInflater.inflate(R.menu.squishy_feedback_menu, menu.menu)
        // Gründe übernehmen eine vorhandene negative Stärke, sonst 1. „Mag ich gar nicht“ ist 3.
        val negative = minOf(currentScore(item), -1)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.squishy_passt_nicht -> record(activity, item, queueTitle, negative, "passt_nicht", null, "knopf", onDone)
                R.id.squishy_mag_nicht -> record(activity, item, queueTitle, -MAX_STRENGTH, "mag_nicht", null, "knopf", onDone)
                R.id.squishy_falsche_version -> record(activity, item, queueTitle, negative, "falsche_version", null, "knopf", onDone)
                R.id.squishy_defekt -> record(activity, item, queueTitle, negative, "defekt", null, "knopf", onDone)
                R.id.squishy_spaeter -> record(activity, item, queueTitle, 0, "spaeter", null, "knopf", onDone)
                R.id.squishy_zuruecknehmen -> record(activity, item, queueTitle, 0, "zurueckgenommen", null, "knopf", onDone)
                R.id.squishy_notiz -> askText(activity, R.string.squishy_notiz, null) { text ->
                    record(activity, item, queueTitle, null, null, text, "knopf", onDone)
                }
                R.id.squishy_einrichten -> setup(activity, force = true) {}
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        menu.show()
    }

    /**
     * [score] null heißt: Bewertung bleibt (nur Notiz). 0 mit Grund „spaeter“ oder
     * „zurueckgenommen“ löscht sie.
     */
    private fun record(
        activity: MainActivity, item: MediaItem, listTitle: String?, score: Int?,
        grund: String?, notiz: String?, quelle: String, onDone: () -> Unit, undo: Boolean = false
    ) {
        init(activity)
        setup(activity, force = false) {
            activity.lifecycleScope.launch {
                val event = buildEvent(activity, item, listTitle, score, grund, notiz, quelle)
                val error = withContext(Dispatchers.IO) {
                    runCatching { append(activity.contentResolver, event) }.exceptionOrNull()
                }
                if (error != null) {
                    Toast.makeText(activity, activity.getString(R.string.squishy_fehler,
                        error.message), Toast.LENGTH_LONG).show()
                    return@launch
                }
                val situation = event.opt("playlist") as? String
                val previous = score(situation, item.mediaId)
                if (score != null) {
                    prefs!!.edit {
                        if (score == 0) remove(scoreKey(situation, item.mediaId))
                        else putInt(scoreKey(situation, item.mediaId), score)
                        remove(VERDICT_PREFIX + (situation ?: "") + "|" + item.mediaId)
                    }
                }
                val text = activity.getString(R.string.squishy_gespeichert,
                    label(activity, score, grund, notiz),
                    situation ?: activity.getString(R.string.squishy_ohne_playlist))
                if (undo && score != null && previous != score) {
                    Snackbar.make(activity.findViewById(R.id.container), text, 5000)
                        .setAction(R.string.squishy_rueckgaengig) {
                            record(activity, item, listTitle, previous,
                                when {
                                    previous == 0 -> "zurueckgenommen"
                                    previous < 0 -> "passt_nicht"
                                    else -> null
                                }, null, quelle, onDone)
                        }
                        .show()
                } else {
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
                }
                onDone()
            }
        }
    }

    private fun label(context: Context, score: Int?, grund: String?, notiz: String?): String {
        val base = context.getString(when {
            notiz != null -> R.string.squishy_notiz
            grund == "zurueckgenommen" -> R.string.squishy_zurueckgenommen
            grund == "spaeter" -> R.string.squishy_spaeter
            score != null && score > 0 -> R.string.squishy_ja
            grund == "mag_nicht" -> R.string.squishy_mag_nicht
            grund == "falsche_version" -> R.string.squishy_falsche_version
            grund == "defekt" -> R.string.squishy_defekt
            else -> R.string.squishy_passt_nicht
        })
        return if (score == null || score == 0) base
        else base + " " + (if (score > 0) "+" else "−").repeat(kotlin.math.abs(score))
    }

    private suspend fun buildEvent(
        activity: MainActivity, item: MediaItem, listTitle: String?, score: Int?,
        grund: String?, notiz: String?, quelle: String
    ): JSONObject {
        val urteil = when {
            score == null -> null
            grund == "spaeter" -> SPAETER
            score > 0 -> JA
            score < 0 -> NEIN
            else -> null
        }
        val playlists = activity.reader.playlistListFlow.first()
            .filter { it.javaClass == Playlist::class.java }
        val list = if (listTitle == null) null else playlists.firstOrNull { it.title == listTitle }
            ?.takeIf { pl -> pl.songList.any { it.mediaId == item.mediaId } }
        val situation = situationOf(list?.title)
        val parent = playlists.firstOrNull { it.title == situation }
        val beschreibung = withContext(Dispatchers.IO) {
            list?.path?.let { readDescription(it) } ?: parent?.path?.let { readDescription(it) }
        }
        val meta = item.mediaMetadata
        return JSONObject().apply {
            put("zeit", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("person", prefs!!.getString(KEY_PERSON, null))
            put("playlist", situation ?: JSONObject.NULL)
            put("liste", list?.title ?: JSONObject.NULL)
            put("beschreibung", beschreibung ?: JSONObject.NULL)
            put("warteschlange", queueTitle ?: JSONObject.NULL)
            put("pfad", item.getFile()?.path ?: JSONObject.NULL)
            put("interpret", meta.artist?.toString() ?: JSONObject.NULL)
            put("titel", meta.title?.toString() ?: JSONObject.NULL)
            put("album", meta.albumTitle?.toString() ?: JSONObject.NULL)
            put("urteil", urteil ?: JSONObject.NULL)
            put("staerke", if (score == null || score == 0) JSONObject.NULL else kotlin.math.abs(score))
            put("grund", (if (grund == "spaeter") null else grund) ?: JSONObject.NULL)
            put("notiz", notiz ?: JSONObject.NULL)
            put("quelle", quelle)
            put("app", BuildConfig.VERSION_NAME)
        }
    }

    fun readDescription(file: File): String? = runCatching {
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
