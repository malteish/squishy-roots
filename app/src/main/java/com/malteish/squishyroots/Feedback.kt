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
import android.media.MediaScannerConnection
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * Abgelehnte Titel landen im Papierkorb `<person>-abgelehnt`, einer Liste pro Person. Jeder Eintrag
 * merkt sich, aus welcher Liste er kam. Dort gilt für einen Titel die Situation seiner Herkunft.
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
    const val TRASH_SUFFIX = "-abgelehnt"
    private const val PASST_NICHT = "passt_nicht"
    private const val MAG_NICHT = "mag_nicht"
    private const val ZURUECKGENOMMEN = "zurueckgenommen"
    private const val RUECKGAENGIG = "rueckgaengig"

    /** Situation einer Liste: `x-kandidaten` gehört zu `x`. */
    fun situationOf(listTitle: String?): String? = listTitle?.removeSuffix(CANDIDATE_SUFFIX)

    /** Vorschläge einer Situation, keine eigene Situation. */
    fun isSubList(listTitle: String?): Boolean = listTitle?.endsWith(CANDIDATE_SUFFIX) == true

    /** Ein Papierkorb, der eigene oder der einer anderen Person. */
    fun isTrash(listTitle: String?): Boolean = listTitle?.endsWith(TRASH_SUFFIX) == true

    /** Der Papierkorb der eingestellten Person, z. B. `malte-abgelehnt`. */
    fun trashTitle(): String? = prefs?.getString(KEY_PERSON, null)?.let { it + TRASH_SUFFIX }

    /** Situation eines Titels in der Liste [listTitle]. Im Papierkorb die seiner neuesten Herkunft. */
    fun situationFor(listTitle: String?, item: MediaItem): String? =
        if (isTrash(listTitle)) situationOf(trashOrigins(item).firstOrNull())
        else situationOf(listTitle)

    /** Herkunft der Einträge im Papierkorb, zuletzt gelesen oder geschrieben. */
    private var trashDir: String? = null
    private var trashEntries: List<Pair<String, List<String>>> = emptyList()

    /** Aus welchen Listen [item] in den Papierkorb kam, neueste zuerst. Leer, wenn er nicht drin ist. */
    fun trashOrigins(item: MediaItem): List<String> {
        val path = item.getFile()?.path ?: return emptyList()
        val dir = trashDir ?: return emptyList()
        return trashEntries.firstOrNull { PlaylistFiles.matches(it.first, path, dir) }?.second
            ?: emptyList()
    }

    /** Liest die Herkunft im Papierkorb neu ein, aus [file] oder der bekannten Playlist. */
    suspend fun loadTrash(activity: MainActivity, file: File? = null) {
        val f = file ?: activity.reader.playlistListFlow.first()
            .firstOrNull { it.javaClass == Playlist::class.java && it.title == trashTitle() }?.path
            ?: return
        val entries = withContext(Dispatchers.IO) { PlaylistFiles.trashOrigins(f) }
        trashDir = f.parent ?: ""
        trashEntries = entries
    }

    /** Kurzer Name einer Herkunft: ohne Person vorn, Vorschläge als solche benannt. */
    fun originLabel(origin: String): String {
        val person = prefs?.getString(KEY_PERSON, null)
        val name = if (person != null) origin.removePrefix("$person-") else origin
        return if (name.endsWith(CANDIDATE_SUFFIX)) name.removeSuffix(CANDIDATE_SUFFIX) + " (Vorschlag)"
        else name
    }

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
        if (item == null) 0 else score(situationFor(queueTitle, item), item.mediaId)

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
        record(activity, item, queueTitle, next, if (next < 0) PASST_NICHT else null, null,
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
        val side = row.height()
        val spot = if (up) android.graphics.Rect(row.left, row.top, row.left + side, row.bottom)
        else android.graphics.Rect(row.right - side, row.top, row.right, row.bottom)
        // Im Papierkorb heißt nach rechts wischen: zurücklegen.
        if (isTrash(listTitle)) {
            if (up) restore(activity, list, item, listTitle, "wischen", spot, onDone)
            return
        }
        val next = step(score(situationOf(listTitle), item.mediaId), up)
        Burst.play(activity, list, up, kotlin.math.abs(next), spot)
        record(activity, item, listTitle, next, if (next < 0) PASST_NICHT else null, null,
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
        val current = score(situationFor(listTitle, item), item.mediaId)
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
                    else -> PASST_NICHT
                }, null, quelle, onDone)
            }
            R.id.squishy_mag_nicht -> {
                Burst.play(activity, anchor, false, MAX_STRENGTH)
                record(activity, item, listTitle, -MAX_STRENGTH, MAG_NICHT, null, quelle, onDone)
            }
            R.id.squishy_spaeter -> record(activity, item, listTitle, 0, "spaeter", null, quelle, onDone)
            R.id.squishy_zuruecknehmen ->
                if (isTrash(listTitle)) restore(activity, anchor, item, listTitle!!, quelle, null, onDone)
                else record(activity, item, listTitle, 0, ZURUECKGENOMMEN, null, quelle, onDone)
            R.id.squishy_notiz -> askText(activity, R.string.squishy_notiz, null) { text ->
                record(activity, item, listTitle, null, null, text, quelle, onDone)
            }
            R.id.squishy_einrichten -> setup(activity, force = true) {}
            else -> return false
        }
        return true
    }

    /**
     * Zurücklegen aus dem Papierkorb: Der Titel kommt in jede Liste zurück, aus der er kam, und
     * die Bewertung in deren Situation wird zurückgenommen (je ein Ereignis).
     */
    private fun restore(
        activity: MainActivity, anchor: View, item: MediaItem, trash: String, quelle: String,
        area: android.graphics.Rect?, onDone: () -> Unit
    ) {
        val origins = trashOrigins(item)
        Burst.play(activity, anchor, true, 1, area, R.drawable.ic_squishy_restore)
        if (origins.isEmpty()) record(activity, item, trash, 0, ZURUECKGENOMMEN, null, quelle, onDone)
        else origins.forEach {
            record(activity, item, trash, 0, ZURUECKGENOMMEN, null, quelle, onDone, origin = it)
        }
    }

    /**
     * [score] null heißt: Bewertung bleibt (nur Notiz). 0 mit Grund „spaeter“ oder
     * „zurueckgenommen“ löscht sie. [origin] wählt im Papierkorb die Herkunft, sonst die neueste.
     */
    private fun record(
        activity: MainActivity, item: MediaItem, listTitle: String?, score: Int?,
        grund: String?, notiz: String?, quelle: String, onDone: () -> Unit, origin: String? = null,
        listChange: Boolean = true
    ) {
        init(activity)
        val from = origin ?: if (isTrash(listTitle)) trashOrigins(item).firstOrNull() else null
        val situation = if (isTrash(listTitle)) situationOf(from) else situationOf(listTitle)
        val previous = score(situation, item.mediaId)
        // In einer Liste verschwindet die Zeile erst nach der Frist, bis dahin zeigt sie Rückgängig.
        val label = if (!listChange || quelle == "knopf") null
        else removalLabel(listTitle, from, score, grund)
        submit(activity, situation, item.mediaId, score, onDone,
            { buildEvent(activity, item, listTitle, from, score, grund, notiz, quelle) },
            {
                when {
                    !listChange -> {}
                    label == null -> applyListChange(activity, item, listTitle, from, score, grund)
                    else -> withContext(Dispatchers.Main) {
                        schedule(activity, item, listTitle!!, label, onDone,
                            apply = { applyListChange(activity, item, listTitle, from, score, grund) },
                            undo = {
                                record(activity, item, listTitle, previous,
                                    if (previous == 0) ZURUECKGENOMMEN else RUECKGAENGIG, null,
                                    quelle, onDone, from, listChange = false)
                            })
                    }
                }
            })
    }

    /** Text der Rückgängig-Zeile, wenn die Änderung den Titel aus [listTitle] nimmt, sonst null. */
    private fun removalLabel(listTitle: String?, origin: String?, score: Int?, grund: String?): Int? {
        if (listTitle == null || score == null) return null
        return when {
            isTrash(listTitle) -> when {
                listTitle != trashTitle() || origin == null -> null
                score == 0 && grund == ZURUECKGENOMMEN -> R.string.squishy_rueck_zurueckgelegt
                score > 0 -> R.string.squishy_rueck_uebernommen
                else -> null
            }
            score < 0 && (grund == PASST_NICHT || grund == MAG_NICHT) -> R.string.squishy_rueck_papierkorb
            score > 0 && isSubList(listTitle) -> R.string.squishy_rueck_uebernommen
            else -> null
        }
    }

    /**
     * Eine aufgeschobene Änderung an einer M3U. Die Zeile bleibt [UNDO_MS] lang stehen und zeigt
     * an ihrem Platz „Rückgängig“, ohne etwas zu verdecken. Danach wird die Datei geändert, die
     * Zeile fällt weg und die folgenden rücken nach.
     */
    private class Pending(val job: Job, val label: Int, val apply: suspend () -> Unit, val undo: () -> Unit)

    private val pending = mutableMapOf<String, MutableList<Pending>>()

    private fun pendingKey(listTitle: String, mediaId: String) = "$listTitle|$mediaId"

    /** Text der Rückgängig-Zeile für [mediaId] in [listTitle], oder null. */
    fun pendingLabel(listTitle: String?, mediaId: String): Int? =
        listTitle?.let { pending[pendingKey(it, mediaId)]?.firstOrNull()?.label }

    private fun schedule(
        activity: MainActivity, item: MediaItem, listTitle: String, label: Int, onDone: () -> Unit,
        apply: suspend () -> Unit, undo: () -> Unit
    ) {
        val key = pendingKey(listTitle, item.mediaId)
        lateinit var entry: Pending
        val job = activity.lifecycleScope.launch {
            delay(UNDO_MS)
            pending[key]?.remove(entry)
            if (pending[key]?.isEmpty() == true) pending.remove(key)
            withContext(Dispatchers.IO + NonCancellable) { runCatching { apply() } }
            onDone()
        }
        entry = Pending(job, label, apply, undo)
        pending.getOrPut(key) { mutableListOf() }.add(entry)
        onDone()
    }

    /** Tipp auf eine Rückgängig-Zeile. true, wenn es eine war. */
    fun undo(activity: MainActivity, anchor: View?, listTitle: String?, item: MediaItem): Boolean {
        val key = listTitle?.let { pendingKey(it, item.mediaId) } ?: return false
        val entries = pending.remove(key) ?: return false
        entries.forEach { it.job.cancel() }
        if (anchor != null) Burst.play(activity, anchor, true, 1, null, R.drawable.ic_squishy_restore)
        entries.forEach { it.undo() }
        return true
    }

    /** Führt alle aufgeschobenen Änderungen sofort aus, z. B. wenn die Liste verlassen wird. */
    fun flushPending() {
        val all = pending.values.flatten()
        pending.clear()
        all.forEach { p ->
            p.job.cancel()
            CoroutineScope(Dispatchers.IO + NonCancellable).launch { runCatching { p.apply() } }
        }
    }

    /** Wie lange eine Zeile Rückgängig anbietet. */
    private const val UNDO_MS = 6000L

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

    /** Immer nur eine Änderung an den M3U-Dateien zugleich (z. B. Zurücklegen in zwei Listen). */
    private val fileLock = Mutex()

    /**
     * Ändert die M3U-Dateien sofort:
     * - Ablehnung („passt nicht“, „mag ich gar nicht“) verschiebt den Titel aus der angezeigten
     *   Liste in den Papierkorb (wird bei Bedarf angelegt) und merkt sich die Herkunft.
     * - Zustimmung in `x-kandidaten` verschiebt ihn nach `x`.
     * - Zurücknehmen legt ihn aus dem Papierkorb in die Herkunft zurück, auch aus dem Player.
     * - Im Papierkorb verschiebt Zustimmung ihn nach `x` (Situation der Herkunft).
     * Defekt, falsche Version und später lassen die Liste unverändert, weil der Titel dort
     * bleiben soll.
     */
    private suspend fun applyListChange(
        activity: MainActivity, item: MediaItem, listTitle: String?, origin: String?,
        score: Int?, grund: String?
    ) {
        fileLock.withLock { changeLists(activity, item, listTitle, origin, score, grund) }
    }

    private suspend fun changeLists(
        activity: MainActivity, item: MediaItem, listTitle: String?, origin: String?,
        score: Int?, grund: String?
    ) {
        if (listTitle == null || score == null) return
        val trash = trashTitle() ?: return
        val itemPath = item.getFile()?.path ?: return
        val playlists = activity.reader.playlistListFlow.first()
            .filter { it.javaClass == Playlist::class.java }
        fun fileOf(title: String?) = playlists.firstOrNull { it.title == title }?.path
        val resolver = activity.contentResolver
        val tree = Uri.parse(prefs!!.getString(KEY_TREE, null)!!)
        val takeBack = score == 0 && grund == ZURUECKGENOMMEN
        var created: File? = null
        val trashFile: File?
        when {
            isTrash(listTitle) -> {
                if (listTitle != trash || origin == null) return
                trashFile = fileOf(trash) ?: return
                val to = when {
                    takeBack -> fileOf(origin) ?: fileOf(situationOf(origin))
                    score > 0 -> fileOf(situationOf(origin)) ?: fileOf(origin)
                    else -> null
                } ?: return
                if (!PlaylistFiles.restore(resolver, tree, trashFile.name, to.name,
                        trashFile.parent ?: "", itemPath, origin)) return
            }
            score < 0 && (grund == PASST_NICHT || grund == MAG_NICHT) -> {
                val from = fileOf(listTitle) ?: return
                trashFile = fileOf(trash) ?: File(from.parentFile, "$trash.${from.extension}")
                    .also { created = it }
                if (!PlaylistFiles.moveToTrash(resolver, tree, from.name, trashFile.name,
                        from.parent ?: "", itemPath, listTitle,
                        activity.getString(R.string.squishy_papierkorb_beschreibung))) return
            }
            score > 0 && isSubList(listTitle) -> {
                val from = fileOf(listTitle) ?: return
                val to = fileOf(situationOf(listTitle)) ?: return
                if (!PlaylistFiles.move(resolver, tree, from.name, to.name, from.parent ?: "",
                        itemPath)) return
                trashFile = null
            }
            takeBack -> {
                // Im Player: Der Titel steht noch in der Warteschlange, aber schon im Papierkorb.
                trashFile = fileOf(trash) ?: return
                val to = fileOf(listTitle) ?: return
                if (!PlaylistFiles.restore(resolver, tree, trashFile.name, to.name,
                        trashFile.parent ?: "", itemPath, listTitle)) return
            }
            else -> return
        }
        if (trashFile != null) loadTrash(activity, trashFile)
        // Eine neu angelegte Liste kennt die Mediathek erst nach dem Scan.
        val newFile = created
        if (newFile == null) refreshLater(activity)
        else MediaScannerConnection.scanFile(activity.applicationContext,
            arrayOf(newFile.path), null) { _, _ ->
            activity.runOnUiThread { refreshLater(activity) }
        }
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
        recordPlaylist(activity, situation, next, if (next < 0) PASST_NICHT else null, null,
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
                    recordPlaylist(activity, situation, 0, ZURUECKGENOMMEN, null, onDone)
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
        activity: MainActivity, item: MediaItem, listTitle: String?, origin: String?, score: Int?,
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
        // Ein abgelehnter Titel steht noch in der Warteschlange, aber schon im Papierkorb.
        val list = if (listTitle == null) null else playlists.firstOrNull { it.title == listTitle }
            ?.takeIf { pl -> pl.songList.any { it.mediaId == item.mediaId } ||
                    listTitle in trashOrigins(item) }
        val inTrash = isTrash(list?.title)
        val situation = if (inTrash) situationOf(origin) else situationOf(list?.title)
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
            if (inTrash) put("herkunft", origin ?: JSONObject.NULL)
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
