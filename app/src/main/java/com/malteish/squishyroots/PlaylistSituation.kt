/*
 *     Squishy Roots: Playlist als Situation (Beschreibung, Vorschläge, Wischen).
 *
 *     Licensed under the GNU General Public License v3 or later, like Gramophone.
 */

package com.malteish.squishyroots

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.adapters.SongAdapter
import org.akanework.gramophone.ui.fragments.GeneralSubFragment
import uk.akane.libphonograph.items.Playlist

/**
 * Eine Playlist `x` ist eine Situation. Ihre Vorschläge liegen in `x-kandidaten` und sind in der
 * Übersicht ausgeblendet, solange es `x` gibt. Erreichbar sind sie über „Stöbern“ im Kopf von `x`.
 */
object PlaylistSituation {

    /** Adapter, deren Zeilen im Gitter die volle Breite einnehmen (CustomGridLayoutManager). */
    interface FullSpan

    /** Für die Playlist-Übersicht: Vorschlagslisten ausblenden, deren Situation existiert. */
    fun hideCandidateLists(playlists: List<Playlist>): List<Playlist> {
        val titles = playlists.mapNotNull { it.title }.toSet()
        return playlists.filter { pl ->
            val t = pl.title
            t == null || !t.endsWith(Feedback.CANDIDATE_SUFFIX) || Feedback.situationOf(t) !in titles
        }
    }

    /** In [GeneralSubFragment] für Playlists aufrufen. */
    fun attach(
        fragment: Fragment, activity: MainActivity, recyclerView: RecyclerView,
        songAdapter: SongAdapter, playlistFlow: Flow<Playlist?>
    ) {
        val header = HeaderAdapter(activity)
        songAdapter.concatAdapter.addAdapter(0, header)
        var listTitle: String? = null
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            playlistFlow.combine(activity.reader.playlistListFlow) { pl, all -> pl to all }
                .collect { (pl, all) ->
                    val title = pl?.title?.takeIf { pl.javaClass == Playlist::class.java }
                    listTitle = title
                    val situation = Feedback.situationOf(title)
                    val parent = all.firstOrNull { it.title == situation }
                    val candidates = if (title == null || title.endsWith(Feedback.CANDIDATE_SUFFIX)) null
                    else all.firstOrNull { it.title == title + Feedback.CANDIDATE_SUFFIX }
                    val description = withContext(Dispatchers.IO) {
                        pl?.path?.let { Feedback.readDescription(it) }
                            ?: parent?.path?.let { Feedback.readDescription(it) }
                    }
                    if (songAdapter.squishySituation != situation) {
                        songAdapter.squishySituation = situation
                        songAdapter.notifyItemRangeChanged(0, songAdapter.itemCount)
                    }
                    // Kommt der Kopf erst nach dem ersten Layout, bliebe er oberhalb des Sichtbaren.
                    val atTop = !recyclerView.canScrollVertically(-1)
                    header.update(description, candidates)
                    if (atTop) recyclerView.scrollToPosition(0)
                }
        }
        ItemTouchHelper(SwipeCallback(activity, songAdapter) { listTitle })
            .attachToRecyclerView(recyclerView)
        // Bewertungen im Player sollen die Markierung in der Liste sofort ändern.
        val prefs = Feedback.preferences(activity)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith(Feedback.SCORE_PREFIX) == true)
                songAdapter.notifyItemRangeChanged(0, songAdapter.itemCount)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        fragment.viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        })
    }

    private class HeaderAdapter(val activity: MainActivity) :
        RecyclerView.Adapter<HeaderAdapter.Holder>(), FullSpan {
        private var description: String? = null
        private var candidates: Playlist? = null

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val description: TextView = view.findViewById(R.id.squishy_beschreibung)
            val browse: MaterialButton = view.findViewById(R.id.squishy_stoebern)
        }

        fun update(description: String?, candidates: Playlist?) {
            val wasShown = itemCount == 1
            this.description = description
            this.candidates = candidates
            val shown = itemCount == 1
            when {
                wasShown && shown -> notifyItemChanged(0)
                wasShown -> notifyItemRemoved(0)
                shown -> notifyItemInserted(0)
            }
        }

        override fun getItemCount() = if (description != null || candidates != null) 1 else 0

        override fun getItemViewType(position: Int) = R.layout.squishy_situation_header

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.description.isVisible = description != null
            holder.description.text = description
            val c = candidates
            holder.browse.isVisible = c != null
            if (c != null) {
                holder.browse.text = activity.getString(R.string.squishy_stoebern, c.songList.size)
                holder.browse.setOnClickListener {
                    activity.startFragment(GeneralSubFragment()) {
                        putString("Class", c.javaClass.name)
                        putString("Id", c.id?.toString())
                        putInt("Item", R.id.playlist)
                    }
                }
            }
        }
    }

    /**
     * Nach rechts: passt hierher. Nach links: passt nicht hierher. Die Zeile wird nie ganz
     * weggewischt, sondern federt zurück. Ausgelöst wird beim Loslassen, wenn sie weit genug
     * gezogen war.
     */
    private class SwipeCallback(
        val activity: MainActivity, val songAdapter: SongAdapter, val listTitle: () -> String?
    ) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private val paint = Paint()
        private val up = AppCompatResources.getDrawable(activity, R.drawable.ic_squishy_thumb_up_filled)!!
        private val down = AppCompatResources.getDrawable(activity, R.drawable.ic_squishy_thumb_down_filled)!!
        private var lastDx = 0f

        override fun getSwipeDirs(rv: RecyclerView, holder: RecyclerView.ViewHolder): Int =
            if (holder.bindingAdapter === songAdapter && listTitle() != null)
                super.getSwipeDirs(rv, holder) else 0

        override fun onMove(
            rv: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ) = false

        override fun getSwipeThreshold(holder: RecyclerView.ViewHolder) = Float.MAX_VALUE

        override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(rv, holder)
            val dx = lastDx
            lastDx = 0f
            if (kotlin.math.abs(dx) < holder.itemView.width * TRIGGER) return
            val pos = holder.bindingAdapterPosition
            val title = listTitle()
            val items = songAdapter.getSongList()
            if (pos == RecyclerView.NO_POSITION || pos >= items.size || title == null) return
            val item = items[pos]
            Feedback.onSwipe(activity, item, title, dx > 0) {
                val p = songAdapter.getSongList().indexOfFirst { it.mediaId == item.mediaId }
                if (p >= 0) songAdapter.notifyItemChanged(p)
            }
        }

        override fun onChildDraw(
            c: Canvas, rv: RecyclerView, holder: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            val v = holder.itemView
            if (isCurrentlyActive) lastDx = dX
            if (dX != 0f) {
                val right = dX > 0
                paint.color = MaterialColors.getColor(v, if (right)
                    com.google.android.material.R.attr.colorPrimaryContainer
                else com.google.android.material.R.attr.colorErrorContainer)
                val icon = if (right) up else down
                icon.setTint(MaterialColors.getColor(v, if (right)
                    com.google.android.material.R.attr.colorOnPrimaryContainer
                else com.google.android.material.R.attr.colorOnErrorContainer))
                val size = icon.intrinsicWidth
                val margin = size
                val top = v.top + (v.height - size) / 2
                if (right) {
                    c.drawRect(v.left.toFloat(), v.top.toFloat(), v.left + dX, v.bottom.toFloat(), paint)
                    icon.setBounds(v.left + margin, top, v.left + margin + size, top + size)
                } else {
                    c.drawRect(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), paint)
                    icon.setBounds(v.right - margin - size, top, v.right - margin, top + size)
                }
                icon.draw(c)
            }
            super.onChildDraw(c, rv, holder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    /** Anteil der Zeilenbreite, ab dem Wischen auslöst. */
    private const val TRIGGER = 0.3f

    /** Markierung bewerteter Titel in Listen einer Situation: je stärker, desto größer. */
    fun decorate(subtitle: TextView, situation: String?, mediaId: String) {
        if (situation == null) return
        val score = Feedback.score(situation, mediaId)
        if (score == 0) return
        val mark = SpannableString(if (score > 0) "👍" else "👎")
        mark.setSpan(RelativeSizeSpan(LIST_SCALE[kotlin.math.abs(score) - 1]), 0, mark.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        subtitle.text = SpannableStringBuilder(mark).append(" ").append(subtitle.text)
    }

    /** Schriftgröße der Markierung relativ zum Untertitel, für Stärke 1 bis 3. */
    private val LIST_SCALE = floatArrayOf(0.75f, 1.1f, 1.45f)
}
