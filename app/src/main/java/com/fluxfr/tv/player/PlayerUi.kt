package com.fluxfr.tv.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.fluxfr.tv.R

/**
 * Langage visuel commun à toutes les vues du lecteur (commandes, panneaux, cartes, liste des chaînes,
 * écran de pause, aperçu) : cartes #E6101218 arrondies à 16 dp, Poppins, texte blanc / atténué,
 * accent #378ADD, élément sélectionné = fond blanc et contenu noir.
 */
internal object PlayerUi {
    const val CARD = 0xE6101218.toInt()
    const val PANEL = 0xF2101218.toInt()
    const val SECONDARY = 0xFFA9AEBB.toInt()
    const val ACCENT = 0xFF378ADD.toInt()
    const val LIVE_RED = 0xFFE5484D.toInt()
    const val RADIUS = 16f
    /** Fond discret d'un bouton / d'une ligne non sélectionné(e). */
    const val SUBTLE = 0x26FFFFFF

    enum class Weight { REGULAR, MEDIUM, SEMIBOLD, BOLD }

    private val fonts = HashMap<Weight, Typeface>()

    fun font(ctx: Context, w: Weight): Typeface = fonts.getOrPut(w) {
        val res = when (w) {
            Weight.REGULAR -> R.font.poppins_regular
            Weight.MEDIUM -> R.font.poppins_medium
            Weight.SEMIBOLD -> R.font.poppins_semibold
            Weight.BOLD -> R.font.poppins_bold
        }
        runCatching { ResourcesCompat.getFont(ctx, res) }.getOrNull()
            ?: if (w == Weight.REGULAR) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
    }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Float = RADIUS) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * ctx.resources.displayMetrics.density
    }

    fun text(ctx: Context, sizeSp: Float, color: Int, bold: Boolean = false) =
        text(ctx, sizeSp, color, if (bold) Weight.SEMIBOLD else Weight.REGULAR)

    fun text(ctx: Context, sizeSp: Float, color: Int, weight: Weight) = TextView(ctx).apply {
        setTextColor(color)
        textSize = sizeSp
        typeface = font(ctx, weight)
        includeFontPadding = false
    }

    /** Chiffres à chasse fixe (les temps ne « dansent » pas pendant la lecture). */
    fun tabular(tv: TextView) = tv.apply { fontFeatureSettings = "tnum" }

    /** Texte blanc, noir quand l'élément (ou son parent) a le focus / est appuyé. */
    fun focusTextColors(normal: Int = Color.WHITE): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(),
        ),
        intArrayOf(Color.BLACK, Color.BLACK, normal),
    )

    /** Fond « sélectionnable » : blanc au focus / à l'appui, [normal] sinon (transparent si 0). */
    fun focusBackground(ctx: Context, radiusDp: Float, normal: Int = 0): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, Color.WHITE, radiusDp))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(ctx, 0xE6FFFFFF.toInt(), radiusDp))
        addState(intArrayOf(), if (normal == 0) ColorDrawable(Color.TRANSPARENT) else rounded(ctx, normal, radiusDp))
    }
}
