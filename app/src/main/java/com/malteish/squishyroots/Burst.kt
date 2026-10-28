/*
 *     Squishy Roots: kurzer Effekt nach einer Bewertung statt Toast oder Snackbar.
 *
 *     Licensed under the GNU General Public License v3 or later, like Gramophone.
 */

package com.malteish.squishyroots

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.animation.doOnEnd
import com.google.android.material.color.MaterialColors
import org.akanework.gramophone.R

/**
 * Ein Daumen steigt von der Stelle auf, an der bewertet wurde, wird größer und verblasst.
 * Er liegt im Overlay des Fensters, verdeckt also nichts dauerhaft und fängt keine Berührungen ab.
 */
object Burst {
    private const val DURATION_MS = 700L
    private const val RISE_DP = 110f

    /** [area] in Koordinaten von [anchor]. Ohne [area] gilt die ganze Ansicht. */
    fun play(activity: Activity, anchor: View, up: Boolean, strength: Int, area: Rect? = null) {
        val root = activity.window.decorView as ViewGroup
        val icon = AppCompatResources.getDrawable(activity, if (up)
            R.drawable.ic_squishy_thumb_up_filled else R.drawable.ic_squishy_thumb_down_filled)
            ?.mutate() ?: return
        icon.setTint(MaterialColors.getColor(anchor, if (up)
            androidx.appcompat.R.attr.colorPrimary else androidx.appcompat.R.attr.colorError))
        val density = activity.resources.displayMetrics.density
        val start = (24 + 8 * strength.coerceIn(1, Feedback.MAX_STRENGTH)) * density
        val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
        val r = area ?: Rect(0, 0, anchor.width, anchor.height)
        val cx = loc[0] + r.exactCenterX()
        val cy = loc[1] + r.exactCenterY()
        val rise = RISE_DP * density
        root.overlay.add(icon)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val half = start * (1f + 0.8f * t) / 2
                val y = cy - rise * t
                icon.setBounds((cx - half).toInt(), (y - half).toInt(), (cx + half).toInt(), (y + half).toInt())
                icon.alpha = ((1f - t) * 255).toInt()
            }
            doOnEnd { root.overlay.remove(icon) }
            start()
        }
    }
}
