package com.fluxfr.tv.player

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/** Icônes vectorielles du lecteur (dessinées : aucun émoji, rendu identique sur toutes les TV). */
internal enum class PlayerIcon { PLAY, PAUSE, REW10, FWD10, NEXT, RESTART, LIVE, CHANNELS, SUBTITLES, QUALITY, BACK, CHECK }

/**
 * Icône dessinée sur une grille de 24 × 24 ; la couleur suit l'état de la vue (blanche, noire au focus)
 * via [colors].
 */
internal class IconDrawable(
    icon: PlayerIcon,
    private val sizePx: Int,
    private val colors: ColorStateList,
    private val typeface: Typeface,
) : Drawable() {
    var icon: PlayerIcon = icon
        set(v) { if (field != v) { field = v; invalidateSelf() } }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 7.2f
    }
    private val path = Path()
    private val rect = RectF()
    private var color = colors.defaultColor

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val c = colors.getColorForState(state, colors.defaultColor)
        if (c == color) return false
        color = c
        invalidateSelf()
        return true
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val side = minOf(b.width(), b.height()).toFloat()
        if (side <= 0f) return
        val s = side / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - side) / 2f, b.top + (b.height() - side) / 2f)
        canvas.scale(s, s)
        fill.color = color
        stroke.color = color
        label.color = color
        label.typeface = typeface
        when (icon) {
            PlayerIcon.PLAY -> poly(8f, 5f, 8f, 19f, 19f, 12f).also { canvas.drawPath(path, fill) }
            PlayerIcon.PAUSE -> {
                rect.set(6f, 5f, 10f, 19f); canvas.drawRoundRect(rect, 1f, 1f, fill)
                rect.set(14f, 5f, 18f, 19f); canvas.drawRoundRect(rect, 1f, 1f, fill)
            }
            PlayerIcon.REW10 -> skipArrow(canvas, mirror = false)
            PlayerIcon.FWD10 -> skipArrow(canvas, mirror = true)
            PlayerIcon.NEXT -> {
                poly(5f, 6f, 5f, 18f, 14f, 12f); canvas.drawPath(path, fill)
                rect.set(16f, 6f, 18.5f, 18f); canvas.drawRoundRect(rect, 0.8f, 0.8f, fill)
            }
            PlayerIcon.RESTART -> {
                rect.set(5.5f, 6f, 8f, 18f); canvas.drawRoundRect(rect, 0.8f, 0.8f, fill)
                poly(19f, 6f, 19f, 18f, 10f, 12f); canvas.drawPath(path, fill)
            }
            PlayerIcon.LIVE -> {
                canvas.drawCircle(12f, 12f, 2.6f, fill)
                rect.set(6.5f, 6.5f, 17.5f, 17.5f)
                canvas.drawArc(rect, -40f, 80f, false, stroke)
                canvas.drawArc(rect, 140f, 80f, false, stroke)
                rect.set(2.8f, 2.8f, 21.2f, 21.2f)
                canvas.drawArc(rect, -40f, 80f, false, stroke)
                canvas.drawArc(rect, 140f, 80f, false, stroke)
            }
            PlayerIcon.CHANNELS -> for (y in floatArrayOf(6f, 12f, 18f)) {
                rect.set(3.5f, y - 1.4f, 6.3f, y + 1.4f); canvas.drawRoundRect(rect, 0.6f, 0.6f, fill)
                canvas.drawLine(9.5f, y, 20.5f, y, stroke)
            }
            PlayerIcon.SUBTITLES -> {
                rect.set(3f, 5f, 21f, 19f); canvas.drawRoundRect(rect, 2.5f, 2.5f, stroke)
                canvas.drawLine(7f, 11f, 9.5f, 11f, stroke); canvas.drawLine(12f, 11f, 17f, 11f, stroke)
                canvas.drawLine(7f, 15f, 13f, 15f, stroke); canvas.drawLine(15.5f, 15f, 17f, 15f, stroke)
            }
            PlayerIcon.QUALITY -> {
                val knobs = floatArrayOf(15f, 8f, 13f)
                floatArrayOf(6f, 12f, 18f).forEachIndexed { i, y ->
                    canvas.drawLine(3.5f, y, 20.5f, y, stroke)
                    canvas.drawCircle(knobs[i], y, 2.6f, fill)
                }
            }
            PlayerIcon.BACK -> {
                canvas.drawLine(19.5f, 12f, 5f, 12f, stroke)
                path.reset(); path.moveTo(11f, 5.5f); path.lineTo(4.5f, 12f); path.lineTo(11f, 18.5f)
                canvas.drawPath(path, stroke)
            }
            PlayerIcon.CHECK -> {
                stroke.strokeWidth = 2.4f
                path.reset(); path.moveTo(4.5f, 12.5f); path.lineTo(9.5f, 17.5f); path.lineTo(19.5f, 6.5f)
                canvas.drawPath(path, stroke)
                stroke.strokeWidth = 2f
            }
        }
        canvas.restore()
    }

    /** Flèche circulaire « 10 » : retour (sens antihoraire) ou avance ([mirror]). */
    private fun skipArrow(canvas: Canvas, mirror: Boolean) {
        canvas.save()
        if (mirror) canvas.scale(-1f, 1f, 12f, 12f)
        rect.set(3.5f, 4.5f, 20.5f, 21.5f)
        canvas.drawArc(rect, -90f, 290f, false, stroke)
        poly(8.2f, 4.5f, 12.4f, 1.6f, 12.4f, 7.4f)
        canvas.drawPath(path, fill)
        canvas.restore()
        val fm = label.fontMetrics
        canvas.drawText("10", 12f, 13f - (fm.ascent + fm.descent) / 2f, label)
    }

    private fun poly(vararg p: Float) {
        path.reset()
        path.moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        path.close()
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha; stroke.alpha = alpha; label.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter; stroke.colorFilter = colorFilter; label.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
