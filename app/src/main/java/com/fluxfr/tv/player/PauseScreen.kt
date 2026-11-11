package com.fluxfr.tv.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.fluxfr.tv.player.PlayerUi.SECONDARY
import com.fluxfr.tv.player.PlayerUi.Weight

/** Contenu de l'écran de pause. */
class PauseInfo(
    val title: String,
    /** « S1 · É3 · Titre de l'épisode » (séries). */
    val episodeLine: String?,
    /** « 2019 · 1 h 52 ». */
    val meta: String?,
    val plot: String?,
    val cast: String?,
)

/**
 * Écran de pause façon Netflix : dégradé sombre + titre, épisode, année / durée, résumé, casting.
 * La barre de progression reste celle des commandes (affichées seules par-dessus, en bas).
 */
class PauseScreen(private val ctx: Context, root: FrameLayout, touch: Boolean = false) {
    private val view: FrameLayout
    private val title: TextView
    private val episode: TextView
    private val meta: TextView
    private val plot: TextView
    private val cast: TextView

    val isShown: Boolean get() = view.visibility == View.VISIBLE

    init {
        val dm = ctx.resources.displayMetrics
        fun dp(v: Int) = PlayerUi.dp(ctx, v)
        val maxText = (dm.widthPixels * 0.55f).toInt()
        val eyebrow = PlayerUi.text(ctx, 14f, SECONDARY, Weight.MEDIUM).apply {
            text = "Vous regardez"
            letterSpacing = 0.06f
        }
        title = PlayerUi.text(ctx, 34f, Color.WHITE, Weight.SEMIBOLD).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; maxWidth = maxText
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
            setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
        }
        episode = PlayerUi.text(ctx, 17f, Color.WHITE, Weight.MEDIUM).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = maxText
            setPadding(0, dp(10), 0, 0)
        }
        meta = PlayerUi.text(ctx, 14f, SECONDARY).apply { setPadding(0, dp(8), 0, 0) }
        plot = PlayerUi.text(ctx, 16f, 0xFFE4E7EE.toInt()).apply {
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END; maxWidth = maxText
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(16), 0, 0)
        }
        cast = PlayerUi.text(ctx, 14f, SECONDARY).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; maxWidth = maxText
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(12), 0, 0)
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(eyebrow); addView(title); addView(episode); addView(meta); addView(plot); addView(cast)
        }
        val side = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xF2000000.toInt(), 0xB3000000.toInt(), 0x33000000, 0x00000000),
        )
        val bottom = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xCC000000.toInt(), 0x00000000, 0x00000000),
        )
        view = FrameLayout(ctx).apply {
            background = LayerDrawable(arrayOf(side, bottom))
            visibility = View.GONE
            alpha = 0f
            // Au-dessus de la barre de progression des commandes (à la même hauteur qu'avec les boutons).
            addView(content, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(if (touch) 24 else 48); rightMargin = dp(48)
                bottomMargin = dp(if (touch) 120 else 170)
            })
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    fun show(info: PauseInfo) {
        bind(info)
        if (isShown) return
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(600).start()
    }

    /** Met à jour le contenu (fiche arrivée après l'affichage). */
    fun bind(info: PauseInfo) {
        title.text = info.title
        title.textSize = if (info.title.length > 40) 28f else 34f
        set(episode, info.episodeLine)
        set(meta, info.meta)
        set(plot, info.plot)
        set(cast, info.cast?.let { "Avec : $it" })
    }

    fun hide() {
        if (!isShown) return
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.GONE
    }

    private fun set(v: TextView, s: String?) {
        v.text = s ?: ""
        v.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
    }
}
