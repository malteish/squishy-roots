/*
 *     Squishy Roots: Daumen im Player, Größe nach Stärke der Bewertung.
 *
 *     Licensed under the GNU General Public License v3 or later, like Gramophone.
 */

package com.malteish.squishyroots

import com.google.android.material.button.MaterialButton
import org.akanework.gramophone.R

object PlaylistSituationIcons {
    /** Symbolgröße in dp: ohne Bewertung, dann Stärke 1 bis 3 (3 füllt den 48-dp-Knopf fast). */
    private val SIZE_DP = intArrayOf(24, 18, 30, 42)

    fun apply(button: MaterialButton, strength: Int, up: Boolean) {
        button.setIconResource(when {
            strength == 0 && up -> R.drawable.ic_squishy_thumb_up
            strength == 0 -> R.drawable.ic_squishy_thumb_down
            up -> R.drawable.ic_squishy_thumb_up_filled
            else -> R.drawable.ic_squishy_thumb_down_filled
        })
        button.iconSize = (SIZE_DP[strength] * button.resources.displayMetrics.density).toInt()
    }
}
