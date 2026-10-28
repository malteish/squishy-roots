/*
 *     Squishy Roots: geteilte Ansicht auf Tablets im Querformat.
 *
 *     Licensed under the GNU General Public License v3 or later, like Gramophone.
 */

package com.malteish.squishyroots

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.updateLayoutParams

/**
 * Auf Tablets im Querformat belegt der geöffnete Player die linke Hälfte. Rechts bleibt die
 * Bibliothek (Playlist, Vorschläge) bedienbar. Zugeklappt liegt der Mini-Player links unten.
 * Zurück navigiert dann in der Liste, der Player schließt über seinen Pfeil.
 */
object SplitView {
    fun isActive(context: Context): Boolean {
        val c = context.resources.configuration
        return c.orientation == Configuration.ORIENTATION_LANDSCAPE && c.smallestScreenWidthDp >= 600
    }

    private fun paneWidth(context: Context) = context.resources.displayMetrics.widthPixels / 2

    /** Einmal nach dem Einhängen des Players aufrufen. */
    fun setUpSheet(sheet: View) {
        if (!isActive(sheet.context)) return
        sheet.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            width = paneWidth(sheet.context)
            gravity = Gravity.START
        }
    }

    /** Bei jeder Zustandsänderung des Players: Liste neben dem offenen Player anordnen. */
    fun onPlayerExpanded(container: View, expanded: Boolean) {
        if (!isActive(container.context)) return
        val start = if (expanded) paneWidth(container.context) else 0
        val lp = container.layoutParams as ViewGroup.MarginLayoutParams
        if (lp.marginStart != start) container.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = start
        }
    }
}
