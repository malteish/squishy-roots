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
import android.widget.LinearLayout
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.BuildConfig
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.getFile
import org.akanework.gramophone.logic.gramophoneApplication
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

    fun onThumb(
        activity: MainActivity, button: View, item: MediaItem?, up: Boolean, onDone: () -> Unit
    ) {
        if (item == null) return
        val next = step(currentScore(item), up)
        Burst.play(activity, button, up, kotlin.math.abs(next))
        record(activity, item, queueTitle, next, if (next < 0) "passt_nicht" else null, null,
            "knopf", onDone)
    }

    /**
     * Wischen in einer Playlist: [listTitle] ist die angezeigte Liste. Der Effekt startet in
     * [row] (Koordinaten von [list]) auf der Seite, zu der gewischt wurde.
     */
    fun onSwipe(
        activity: MainActivity, list: View, row: android.graphics.Rect, item: MediaItem,
        listTitle: String, up: Boolean, onDone: () -> Unit
    ) {
        val situation = situationOf(listTitle)
        val next = step(score(situation, item.mediaId), up)
        val side = row.height()
        val spot = if (up) android.graphics.Rect(row.left, row.top, row.left + side, row.bottom)
        else android.graphics.Rect(row.right - side, row.top, row.right, row.bottom)
        Burst.play(activity, list, up, kotlin.math.abs(next), spot)
        record(activity, item, listTitle, next, if (next < 0) "passt_nicht" else null, null,
            "wischen", onDone)
    }

    fun showMenu(activity: MainActivity, anchor: View, item: MediaItem?, onDone: () -> Unit) {
        if (item == null) return
        val menu = PopupMenu(activity, anchor)
        menu.menuInflater.inflate(R.menu.squishy_feedback_menu, menu.menu)
        menu.setOnMenuItemClickListener {
            handleMenuItem(activity, anchor, item, queueTitle, it.itemId, "knopf", onDone)
        }
        menu.show()
    }

    /** Bewertungsmenü als Untermenü im Kontextmenü eines Titels in einer Playlist. */
    fun addListMenu(activity: MainActivity, menu: android.view.Menu) {
        val sub = menu.addSubMenu(R.string.squishy_bewertung)
        activity.menuInflater.inflate(R.menu.squishy_feedback_menu, sub)
    }

    /** Führt einen Eintrag aus [R.menu.squishy_feedback_menu] aus. false: kein solcher Eintrag. */
    fun handleMenuItem(
        activity: MainActivity, anchor: View, item: MediaItem, listTitle: String?, itemId: Int,
        quelle: String, onDone: () -> Unit
    ): Boolean {
        val current = score(situationOf(listTitle), item.mediaId)
        // Gründe übernehmen eine vorhandene negative Stärke, sonst 1. „Mag ich gar nicht“ ist 3.
        val negative = minOf(current, -1)
        when (itemId) {
            R.id.squishy_passt_hierher -> {
                val next = step(current, true)
                Burst.play(activity, anchor, true, next)
                record(activity, item, listTitle, next, null, null, quelle, onDone)
            }
            R.id.squishy_passt_nicht, R.id.squishy_falsche_version, R.id.squishy_defekt -> {
                Burst.play(activity, anchor, false, -negative)
                record(activity, item, listTitle, negative, when (itemId) {
                    R.id.squishy_falsche_version -> "falsche_version"
                    R.id.squishy_defekt -> "defekt"
                    else -> "passt_nicht"
                }, null, quelle, onDone)
            }
            R.id.squishy_mag_nicht -> {
                Burst.play(activity, anchor, false, MAX_STRENGTH)
                record(activity, item, listTitle, -MAX_STRENGTH, "mag_nicht", null, quelle, onDone)
            }
            R.id.squishy_spaeter -> record(activity, item, listTitle, 0, "spaeter", null, quelle, onDone)
            R.id.squishy_zuruecknehmen -> record(activity, item, listTitle, 0, "zurueckgenommen", null, quelle, onDone)
            R.id.squishy_notiz -> askText(activity, R.string.squishy_notiz, null) { text ->
                record(activity, item, listTitle, null, null, text, quelle, onDone)
            }
            R.id.squishy_einrichten -> setup(activity, force = true) {}
            else -> return false
        }
        return true
    }

    /**
     * [score] null heißt: Bewertung bleibt (nur Notiz). 0 mit Grund „spaeter“ oder
     * „zurueckgenommen“ löscht sie.
     */
    private fun record(
        activity: MainActivity, item: MediaItem, listTitle: String?, score: Int?,
        grund: String?, notiz: String?, quelle: String, onDone: () -> Unit
    ) {
        init(activity)
        submit(activity, situationOf(listTitle), item.mediaId, score, onDone,
            { buildEvent(activity, item, listTitle, score, grund, notiz, quelle) },
            { applyListChange(activity, item, listTitle, score, grund) })
    }

    /**
     * Gemeinsamer Weg für jedes Ereignis: Bewertung sofort merken (sofern [score] gesetzt),
     * Zeile ans Log hängen, danach [afterLog]. Schlägt eines davon fehl, wird die Bewertung
     * zurückgesetzt und der Fehler gemeldet.
     */
    private fun submit(
        activity: MainActivity, situation: String?, mediaId: String, score: Int?,
        onDone: () -> Unit, build: suspend () -> JSONObject, afterLog: suspend () -> Unit = {}
    ) {
        init(activity)
        setup(activity, force = false) {
            // Sofort merken: Mehrmals schnell tippen muss auf dem neuen Stand aufbauen.
            val previous = score(situation, mediaId)
            if (score != null) storeScore(situation, mediaId, score)
            onDone()
            activity.lifecycleScope.launch {
                val error = withContext(Dispatchers.IO) {
                    runCatching {
                        append(activity.contentResolver, build())
                        afterLog()
                    }.exceptionOrNull()
                }
                if (error != null) {
                    if (score != null) storeScore(situation, mediaId, previous)
                    onDone()
                    Toast.makeText(activity, activity.getString(R.string.squishy_fehler,
                        error.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Ablehnung („passt nicht“, „mag ich gar nicht“) nimmt den Titel sofort aus der angezeigten
     * Liste, Zustimmung in `x-kandidaten` verschiebt ihn in die Playlist `x`. Defekt, falsche
     * Version und später lassen die Liste unverändert, weil der Titel dort bleiben soll.
     */
    private suspend fun applyListChange(
        activity: MainActivity, item: MediaItem, listTitle: String?, score: Int?, grund: String?
    ) {
        if (listTitle == null || score == null) return
        val candidates = listTitle.endsWith(CANDIDATE_SUFFIX)
        val remove = score < 0 && (grund == "passt_nicht" || grund == "mag_nicht")
        val promote = score > 0 && candidates
        if (!remove && !promote) return
        val itemPath = item.getFile()?.path ?: return
        val playlists = activity.reader.playlistListFlow.first()
            .filter { it.javaClass == Playlist::class.java }
        val from = playlists.firstOrNull { it.title == listTitle }?.path ?: return
        val to = if (promote) playlists.firstOrNull { it.title == situationOf(listTitle) }?.path else null
        if (promote && to == null) return
        val tree = Uri.parse(prefs!!.getString(KEY_TREE, null)!!)
        val changed = if (to != null)
            PlaylistFiles.move(activity.contentResolver, tree, from.name, to.name,
                from.parent ?: "", itemPath)
        else PlaylistFiles.remove(activity.contentResolver, tree, from.name, from.parent ?: "", itemPath)
        if (changed) refreshLater(activity)
    }

    private var refreshJob: Job? = null

    /** Liest die Playlists neu ein, gebündelt, wenn mehrere Titel schnell hintereinander wegfallen. */
    private fun refreshLater(activity: MainActivity) {
        refreshJob?.cancel()
        refreshJob = activity.lifecycleScope.launch {
            delay(REFRESH_DELAY_MS)
            withContext(Dispatchers.Default) { activity.gramophoneApplication.reader.refresh() }
        }
    }

    private const val REFRESH_DELAY_MS = 1000L

    /** Schlüssel für die Bewertung der ganzen Playlist, getrennt von den Titeln. */
    const val PLAYLIST_ITEM = "#playlist"

    fun playlistScore(situation: String?): Int = score(situation, PLAYLIST_ITEM)

    /** Daumen an der Playlist als Ganzes: ein Tipp verändert die Bewertung um eine Stufe. */
    fun onPlaylistThumb(
        activity: MainActivity, button: View, situation: String, up: Boolean, onDone: () -> Unit
    ) {
        val next = step(playlistScore(situation), up)
        Burst.play(activity, button, up, kotlin.math.abs(next))
        recordPlaylist(activity, situation, next, if (next < 0) "passt_nicht" else null, null,
            onDone)
    }

    fun showPlaylistMenu(activity: MainActivity, anchor: View, situation: String, onDone: () -> Unit) {
        val menu = PopupMenu(activity, anchor)
        menu.menuInflater.inflate(R.menu.squishy_playlist_menu, menu.menu)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.squishy_pl_ja -> onPlaylistThumb(activity, anchor, situation, true, onDone)
                R.id.squishy_pl_nein -> onPlaylistThumb(activity, anchor, situation, false, onDone)
                R.id.squishy_pl_zuruecknehmen ->
                    recordPlaylist(activity, situation, 0, "zurueckgenommen", null, onDone)
                R.id.squishy_pl_notiz -> askText(activity, R.string.squishy_notiz, null) { text ->
                    recordPlaylist(activity, situation, null, null, text, onDone)
                }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        menu.show()
    }

    private fun recordPlaylist(
        activity: MainActivity, situation: String, score: Int?, grund: String?, notiz: String?,
        onDone: () -> Unit
    ) {
        submit(activity, situation, PLAYLIST_ITEM, score, onDone, {
            JSONObject().apply {
                put("zeit", now())
                put("person", prefs!!.getString(KEY_PERSON, null))
                put("ziel", "playlist")
                put("playlist", situation)
                put("beschreibung", descriptionOf(activity, situation) ?: JSONObject.NULL)
                put("urteil", when {
                    score == null -> JSONObject.NULL
                    score > 0 -> JA
                    score < 0 -> NEIN
                    else -> JSONObject.NULL
                })
                put("staerke", if (score == null || score == 0) JSONObject.NULL else kotlin.math.abs(score))
                put("grund", grund ?: JSONObject.NULL)
                put("notiz", notiz ?: JSONObject.NULL)
                put("quelle", "kopf")
                put("app", BuildConfig.VERSION_NAME)
            }
        })
    }

    /** Wunsch nach einer neuen Playlist: Name und Beschreibung, damit Claude sie anlegen kann. */
    fun wishPlaylist(activity: MainActivity, anchor: View) {
        val name = EditText(activity).apply {
            hint = activity.getString(R.string.squishy_wunsch_name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val description = EditText(activity).apply {
            hint = activity.getString(R.string.squishy_wunsch_beschreibung)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
        }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(name)
            addView(description)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.squishy_wunsch_titel)
            .setMessage(R.string.squishy_wunsch_hinweis)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val wishName = name.text.toString().trim()
                if (wishName.isEmpty()) return@setPositiveButton
                Burst.play(activity, anchor, true, 1)
                submit(activity, null, "", null, {}, {
                    JSONObject().apply {
                        put("zeit", now())
                        put("person", prefs!!.getString(KEY_PERSON, null))
                        put("ziel", "wunsch")
                        put("art", "neue_playlist")
                        put("name", wishName)
                        put("beschreibung", description.text.toString().trim().ifEmpty { null }
                            ?: JSONObject.NULL)
                        put("quelle", "liste")
                        put("app", BuildConfig.VERSION_NAME)
                    }
                })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        name.requestFocus()
    }

    private fun now() = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private suspend fun descriptionOf(activity: MainActivity, situation: String): String? {
        val playlists = activity.reader.playlistListFlow.first()
            .filter { it.javaClass == Playlist::class.java }
        val file = (playlists.firstOrNull { it.title == situation }
            ?: playlists.firstOrNull { it.title == situation + CANDIDATE_SUFFIX })?.path
        return withContext(Dispatchers.IO) { file?.let { readDescription(it) } }
    }

    private fun storeScore(situation: String?, mediaId: String, score: Int) {
        prefs!!.edit {
            if (score == 0) remove(scoreKey(situation, mediaId))
            else putInt(scoreKey(situation, mediaId), score)
            remove(VERDICT_PREFIX + (situation ?: "") + "|" + mediaId)
        }
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
            put("zeit", now())
            put("person", prefs!!.getString(KEY_PERSON, null))
            put("ziel", "titel")
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
        PlaylistFiles.find(resolver, tree, parentId, name)?.let { return it }
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
