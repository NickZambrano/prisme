package com.fluxfr.tv.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.fluxfr.tv.player.PlayerUi.Weight

/** Ce que montre la barre de progression (VOD, direct enregistré, ou simple guide du direct). */
internal class BarModel(
    /** Barre dessinée (false : seulement les libellés, ex. direct sans guide). */
    val showTrack: Boolean,
    /** Position affichée (0..1) : lecture, ou cible pendant un déplacement. */
    val fraction: Float,
    /** Fin de la partie déjà chargée (0..1). */
    val buffered: Float,
    /** Repères (début / fin du programme), 0..1. */
    val ticks: FloatArray,
    /** La barre peut recevoir le focus et être parcourue. */
    val seekable: Boolean,
    val leftText: CharSequence,
    val rightText: CharSequence?,
    /** Pastille rouge « EN DIRECT ». */
    val livePill: Boolean,
    /** Bulle au-dessus du curseur (pendant un déplacement), null sinon. */
    val bubble: String?,
    /**
     * Direct avec contrôle : la barre représente le programme (ou la fenêtre enregistrée) ; [buffered] = « maintenant »
     * (partie écoulée), [windowStart]..[liveAt] = partie enregistrée (plus claire), repère rouge à [liveAt],
     * curseur = [fraction] (position de lecture).
     */
    val live: Boolean = false,
    val windowStart: Float = 0f,
    /** Repère « direct » (0..1) ; négatif : hors de la barre (programme passé). */
    val liveAt: Float = -1f,
)

/** Bouton de la rangée de commandes. */
internal class OverlayAction(
    val key: String,
    val icon: PlayerIcon,
    val label: String,
    /** Libellé affiché à côté de l'icône (sinon : bouton rond, libellé en description). */
    val showLabel: Boolean,
    val onClick: () -> Unit,
)

/**
 * Commandes du lecteur, une seule présentation pour la VOD, les séries et le direct (TV et téléphone) :
 * dégradés haut / bas, titre en haut à gauche, barre de progression unique avec temps, rangée de
 * boutons à icônes. Le focus (TV) se pose sur les boutons ou sur la barre ; l'activité gère les touches.
 */
internal class ControlOverlay(
    ctx: Context,
    private val touch: Boolean,
    onBack: () -> Unit,
    /** Glissement sur la barre (tactile) : fraction 0..1 et phase (0 début, 1 déplacement, 2 fin). */
    private val onScrub: (Float, Int) -> Unit,
) : FrameLayout(ctx) {
    enum class Mode { FULL, HEADER, PAUSE }

    private fun dp(v: Int) = PlayerUi.dp(context, v)

    private val topScrim = View(ctx).apply {
        // Assez sombre pour lire la ligne d'infos sur une image claire.
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xE6000000.toInt(), 0xA6000000.toInt(), 0x00000000),
        )
    }
    private val bottomScrim = View(ctx).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xEB000000.toInt(), 0x99000000.toInt(), 0x00000000),
        )
    }

    // --- En-tête ---
    private val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val title = PlayerUi.text(ctx, if (touch) 20f else 24f, Color.WHITE, Weight.SEMIBOLD).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(6f, 0f, 1f, 0x80000000.toInt())
    }
    private val subtitle = PlayerUi.text(ctx, if (touch) 15f else 17f, Color.WHITE, Weight.MEDIUM).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(6), 0, 0)
        setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt())
    }
    private val meta = PlayerUi.text(ctx, 14f, 0xFFD5D9E2.toInt()).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(6), 0, 0)
        setShadowLayer(6f, 0f, 1f, 0xE6000000.toInt())
    }

    // --- Bas : barre, temps, boutons ---
    private val bottomBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false; clipToPadding = false
    }
    private val progressRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val bar = ScrubBar(ctx, touch) { f, phase -> onScrub(f, phase) }
    private val leftTime = PlayerUi.tabular(PlayerUi.text(ctx, 15f, Color.WHITE, Weight.MEDIUM))
    private val rightTime = PlayerUi.tabular(PlayerUi.text(ctx, 15f, Color.WHITE, Weight.MEDIUM))
    private val livePill = PlayerUi.text(ctx, 12f, Color.WHITE, Weight.SEMIBOLD).apply {
        text = "EN DIRECT"
        letterSpacing = 0.06f
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = PlayerUi.rounded(ctx, PlayerUi.LIVE_RED, 4f)
    }
    private val badge = PlayerUi.text(ctx, 12f, PlayerUi.SECONDARY, Weight.MEDIUM).apply {
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), 0x66FFFFFF)
        }
        visibility = View.GONE
    }
    private val buttonsRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val leftGroup = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val rightGroup = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    // --- Aperçu pendant un déplacement : image + bulle de temps ---
    private val preview = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
    }
    private val previewImage = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.BLACK)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
            }
        }
        clipToOutline = true
        visibility = View.GONE
    }
    private val bubble = PlayerUi.tabular(PlayerUi.text(ctx, 15f, Color.BLACK, Weight.SEMIBOLD)).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = PlayerUi.rounded(ctx, Color.WHITE, 8f)
    }

    private var actions: List<OverlayAction> = emptyList()
    private var signature = ""
    private val buttons = LinkedHashMap<String, View>()
    private var ppIcon: IconDrawable? = null
    private var playing = true
    var mode = Mode.FULL
        private set
    /** Affichées (ou en cours d'apparition) ; faux dès le début du fondu de masquage. */
    private var showing = false
    val visible: Boolean get() = showing
    /** Bouton choisi faute de ⏯ (direct sans contrôle) : remplacé par ⏯ dès qu'il apparaît. */
    private var fallbackFocusKey: String? = null
    val barFocused: Boolean get() = bar.isFocused
    val hasFocusInside: Boolean get() = findFocus() != null
    private var lastModel: BarModel? = null

    init {
        clipChildren = false
        visibility = View.GONE
        val side = dp(if (touch) 16 else 48)
        addView(topScrim, LayoutParams(-1, dp(if (touch) 140 else 210), Gravity.TOP))
        addView(bottomScrim, LayoutParams(-1, dp(if (touch) 200 else 280), Gravity.BOTTOM))

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(title); addView(subtitle); addView(meta)
        }
        if (touch) {
            val back = ImageView(ctx).apply {
                setImageDrawable(IconDrawable(PlayerIcon.BACK, dp(26), PlayerUi.focusTextColors(), PlayerUi.font(ctx, Weight.SEMIBOLD)))
                scaleType = ImageView.ScaleType.CENTER
                background = PlayerUi.focusBackground(ctx, 24f)
                contentDescription = "Retour"
                isClickable = true
                setOnClickListener { onBack() }
            }
            header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        }
        header.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { if (touch) topMargin = dp(10) })
        addView(header, LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = if (touch) dp(8) else side; rightMargin = side
            topMargin = dp(if (touch) 8 else 36)
        })

        val rightBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(rightTime)
            addView(livePill, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            addView(badge, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        }
        progressRow.addView(leftTime, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(16) })
        progressRow.addView(bar, LinearLayout.LayoutParams(0, dp(if (touch) 32 else 24), 1f))
        progressRow.addView(rightBox, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16) })
        buttonsRow.addView(leftGroup)
        buttonsRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        buttonsRow.addView(rightGroup)
        bottomBox.addView(progressRow, LinearLayout.LayoutParams(-1, -2))
        bottomBox.addView(buttonsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (touch) 6 else 14) })
        addView(bottomBox, LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = side; rightMargin = side; bottomMargin = dp(if (touch) 10 else 30)
        })

        preview.addView(previewImage, LinearLayout.LayoutParams(dp(if (touch) 192 else 240), dp(if (touch) 108 else 135)).apply {
            bottomMargin = dp(8)
        })
        preview.addView(bubble)
        addView(preview, LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
    }

    // ---------------------------------------------------------------------------------------------

    fun setHeader(t: String, sub: String?, metaLine: String?) {
        if (title.text.toString() != t) title.text = t
        set(subtitle, sub)
        set(meta, metaLine)
    }

    /** Badge « 1080p · 5.1 » à droite des temps (null : masqué). */
    fun setBadge(text: String?) = set(badge, text)

    fun setPlaying(p: Boolean) {
        playing = p
        ppIcon?.icon = if (p) PlayerIcon.PAUSE else PlayerIcon.PLAY
        buttons["pp"]?.contentDescription = if (p) "Pause" else "Lecture"
    }

    /** Remplace les boutons (reconstruits seulement si la liste change ; le focus est conservé). */
    fun setButtons(list: List<OverlayAction>, rightKeys: Set<String>) {
        actions = list
        val sig = list.joinToString("|") { it.key + ":" + it.label }
        if (sig == signature) return
        signature = sig
        var focusedKey = buttons.entries.firstOrNull { it.value.isFocused }?.key
        // ⏯ vient d'apparaître (contrôle du direct disponible) : il reprend le focus choisi par défaut.
        if (focusedKey != null && focusedKey == fallbackFocusKey && list.any { it.key == "pp" }) {
            focusedKey = "pp"
            fallbackFocusKey = null
        }
        leftGroup.removeAllViews(); rightGroup.removeAllViews(); buttons.clear(); ppIcon = null
        // TV, beaucoup de boutons à libellé : libellé affiché seulement au focus (la rangée tient dans l'écran).
        compactLabels = !touch && list.count { it.showLabel } > MAX_LABELLED
        val gap = dp(if (touch) 4 else if (compactLabels) 6 else 10)
        for (a in list) {
            val v = makeButton(a)
            buttons[a.key] = v
            val group = if (a.key in rightKeys) rightGroup else leftGroup
            group.addView(v, LinearLayout.LayoutParams(-2, -2).apply {
                if (group.childCount > 0) leftMargin = gap
            })
        }
        val views = buttons.values.toList()
        views.forEachIndexed { i, v ->
            v.nextFocusLeftId = views.getOrNull(i - 1)?.id ?: v.id
            v.nextFocusRightId = views.getOrNull(i + 1)?.id ?: v.id
            v.nextFocusDownId = v.id
            v.nextFocusUpId = if (bar.isFocusable) bar.id else v.id
        }
        bar.nextFocusDownId = (buttons["pp"] ?: views.firstOrNull())?.id ?: View.NO_ID
        setPlaying(playing)
        if (focusedKey != null && !touch && visible) (buttons[focusedKey] ?: buttons["pp"] ?: views.firstOrNull())?.requestFocus()
    }

    private fun makeButton(a: OverlayAction): View {
        val ctx = context
        val iconSize = dp(if (a.showLabel) 22 else 28)
        val icon = IconDrawable(a.icon, iconSize, PlayerUi.focusTextColors(), PlayerUi.font(ctx, Weight.SEMIBOLD))
        if (a.key == "pp") ppIcon = icon
        val v = LinearLayout(ctx).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = PlayerUi.focusBackground(ctx, 100f)
            isFocusable = !touch
            isFocusableInTouchMode = false
            isClickable = true
            contentDescription = a.label
            setOnClickListener { actions.firstOrNull { it.key == a.key }?.onClick?.invoke() }
            val img = ImageView(ctx).apply {
                setImageDrawable(icon)
                isDuplicateParentStateEnabled = true
            }
            addView(img, LinearLayout.LayoutParams(iconSize, iconSize))
            if (a.showLabel) {
                minimumHeight = dp(if (touch) 44 else 48)
                val label = PlayerUi.text(ctx, 15f, Color.WHITE, Weight.MEDIUM).apply {
                    text = a.label
                    setTextColor(PlayerUi.focusTextColors())
                    isDuplicateParentStateEnabled = true
                }
                addView(label, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
                if (compactLabels) {
                    // Rond comme les autres boutons ; le libellé se déplie au focus.
                    val size = dp(52)
                    minimumWidth = size; minimumHeight = size
                    fun apply(focused: Boolean) {
                        label.visibility = if (focused) View.VISIBLE else View.GONE
                        setPadding(if (focused) dp(16) else 0, 0, if (focused) dp(20) else 0, 0)
                    }
                    apply(false)
                    setOnFocusChangeListener { _, f -> apply(f) }
                } else setPadding(dp(16), 0, dp(20), 0)
            } else {
                val size = dp(if (touch) 48 else 52)
                minimumWidth = size; minimumHeight = size
            }
        }
        return v
    }

    fun bindBar(m: BarModel?) {
        lastModel = m
        if (m == null) {
            progressRow.visibility = View.GONE
            preview.visibility = View.GONE
            return
        }
        progressRow.visibility = View.VISIBLE
        bar.visibility = if (m.showTrack) View.VISIBLE else View.INVISIBLE
        bar.bind(m)
        if (bar.isFocusable != m.seekable) {
            bar.isFocusable = m.seekable && !touch
            if (!m.seekable && bar.isFocused) focusPlayPause()
            buttons.values.forEach { it.nextFocusUpId = if (bar.isFocusable) bar.id else it.id }
        }
        if (leftTime.text.toString() != m.leftText.toString()) leftTime.text = m.leftText
        set(rightTime, m.rightText?.toString())
        livePill.visibility = if (m.livePill) View.VISIBLE else View.GONE
        val b = m.bubble
        if (b == null || mode == Mode.HEADER || !m.showTrack) {
            preview.visibility = View.GONE
            previewImage.setImageDrawable(null)
            previewImage.visibility = View.GONE
        } else {
            bubble.text = b
            preview.visibility = View.VISIBLE
            positionPreview()
        }
    }

    /** Image de l'aperçu (null : bulle de temps seule, sans cadre vide). */
    fun setPreviewImage(bmp: Bitmap?) {
        if (bmp == null) {
            previewImage.setImageDrawable(null)
            previewImage.visibility = View.GONE
        } else {
            previewImage.setImageBitmap(bmp)
            previewImage.visibility = View.VISIBLE
        }
        if (preview.visibility == View.VISIBLE) positionPreview()
    }

    private fun positionPreview() {
        if (bar.width == 0) { post { if (preview.visibility == View.VISIBLE) positionPreview() }; return }
        val me = IntArray(2); getLocationInWindow(me)
        val b = IntArray(2); bar.getLocationInWindow(b)
        preview.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val pw = preview.measuredWidth
        val ph = preview.measuredHeight
        val cx = b[0] - me[0] + bar.thumbX()
        val minX = dp(12).toFloat()
        val maxX = (width - pw - dp(12)).toFloat().coerceAtLeast(minX)
        preview.translationX = (cx - pw / 2f).coerceIn(minX, maxX)
        preview.translationY = (b[1] - me[1] + bar.trackY() - ph - dp(14)).toFloat()
    }

    fun show(m: Mode) {
        applyMode(m)
        val already = showing && visibility == View.VISIBLE && alpha == 1f
        showing = true
        if (already) return
        animate().cancel()
        if (visibility != View.VISIBLE) alpha = 0f
        visibility = View.VISIBLE
        animate().alpha(1f).setDuration(160).start()
    }

    fun hide() {
        if (!showing) return
        showing = false
        animate().cancel()
        preview.visibility = View.GONE
        // Le focus quitte les commandes (sinon une touche pourrait « cliquer » un bouton masqué).
        findFocus()?.clearFocus()
        animate().alpha(0f).setDuration(160).withEndAction { if (!showing) visibility = View.GONE }.start()
    }

    private fun applyMode(m: Mode) {
        mode = m
        val full = m == Mode.FULL
        header.visibility = if (m == Mode.PAUSE) View.GONE else View.VISIBLE
        topScrim.visibility = header.visibility
        bottomBox.visibility = if (m == Mode.HEADER) View.GONE else View.VISIBLE
        bottomScrim.visibility = bottomBox.visibility
        // Écran de pause : boutons invisibles mais leur place est gardée (barre à la même hauteur).
        buttonsRow.visibility = when (m) {
            Mode.FULL -> View.VISIBLE
            Mode.PAUSE -> View.INVISIBLE
            Mode.HEADER -> View.GONE
        }
        if (!full) preview.visibility = View.GONE
    }

    /**
     * Focus par défaut : ⏯. Sans ⏯ (direct sans contrôle du direct), un bouton sans effet immédiat sur la
     * lecture (jamais « Début du programme » ni « Direct », qu'un OK réflexe déclencherait).
     */
    fun focusPlayPause() {
        if (touch) return
        val pp = buttons["pp"]
        if (pp != null) { fallbackFocusKey = null; pp.requestFocus(); return }
        val key = SAFE_FALLBACK.firstOrNull { it in buttons } ?: buttons.keys.firstOrNull { it !in RISKY } ?: return
        fallbackFocusKey = key
        buttons[key]?.requestFocus()
    }

    fun focusBar(): Boolean {
        if (touch || !bar.isFocusable || bar.visibility != View.VISIBLE) return false
        return bar.requestFocus()
    }

    fun focusButton(key: String) {
        if (!touch) buttons[key]?.requestFocus()
    }

    /** Hauteur occupée en bas (pour placer la pastille de messages au-dessus). */
    fun bottomHeight(): Int = if (bottomBox.height > 0) bottomBox.height + dp(if (touch) 10 else 30) else dp(160)

    private fun set(v: TextView, s: String?) {
        if (s.isNullOrBlank()) { v.visibility = View.GONE; return }
        if (v.text.toString() != s) v.text = s
        v.visibility = View.VISIBLE
    }

    /** Libellés repliés (affichés au focus) : rangée trop chargée pour l'écran. */
    private var compactLabels = false

    private companion object {
        /** Au-delà, les libellés sont repliés (sinon « Qualité » sortirait de l'écran avec « Direct »). */
        const val MAX_LABELLED = 3
        /** Focus par défaut sans ⏯ : boutons sans effet immédiat sur la lecture. */
        val SAFE_FALLBACK = listOf("channels", "tracks", "quality")
        val RISKY = setOf("restart", "live", "next")
    }
}

/** Barre de progression fine : fond, partie chargée (gris clair), lecture (accent), curseur rond au focus. */
internal class ScrubBar(
    ctx: Context,
    private val touch: Boolean,
    private val onScrub: (Float, Int) -> Unit,
) : View(ctx) {
    private var m: BarModel? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = ctx.resources.displayMetrics.density
    private var dragging = false

    init {
        id = View.generateViewId()
        isFocusable = false
        isFocusableInTouchMode = false
    }

    fun bind(model: BarModel) {
        m = model
        invalidate()
    }

    private val pad get() = 9 * density
    fun trackY(): Int = height / 2

    fun thumbX(): Float {
        val f = m?.fraction ?: 0f
        return pad + f.coerceIn(0f, 1f) * (width - 2 * pad)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val st = m ?: return
        val left = pad
        val right = width - pad
        val cy = height / 2f
        val active = isFocused || dragging
        val th = (if (active) 3f else 2f) * density
        fun x(f: Float) = left + f.coerceIn(0f, 1f) * (right - left)
        paint.color = 0x40FFFFFF
        rect.set(left, cy - th, right, cy + th); canvas.drawRoundRect(rect, th, th, paint)
        paint.color = 0x80D0D4DC.toInt()
        rect.set(left, cy - th, x(st.buffered), cy + th); canvas.drawRoundRect(rect, th, th, paint)
        val px = x(st.fraction)
        if (st.live) {
            // Partie enregistrée (on peut s'y déplacer) : plus claire, jusqu'au direct.
            val lx = if (st.liveAt >= 0f) x(st.liveAt) else right
            paint.color = 0xCCE8EBF0.toInt()
            rect.set(x(st.windowStart), cy - th, lx, cy + th); canvas.drawRoundRect(rect, th, th, paint)
            // Déjà vu jusqu'à la position de lecture.
            paint.color = PlayerUi.ACCENT
            rect.set(x(st.windowStart), cy - th, px, cy + th); canvas.drawRoundRect(rect, th, th, paint)
            if (st.liveAt >= 0f) {
                paint.color = PlayerUi.LIVE_RED
                canvas.drawRect(lx - 1.5f * density, cy - 7 * density, lx + 1.5f * density, cy + 7 * density, paint)
            }
        } else {
            paint.color = PlayerUi.ACCENT
            rect.set(left, cy - th, px, cy + th); canvas.drawRoundRect(rect, th, th, paint)
        }
        paint.color = 0xE6FFFFFF.toInt()
        for (t in st.ticks) {
            if (t <= 0f || t >= 1f) continue
            val tx = x(t)
            canvas.drawRect(tx - density, cy - 6 * density, tx + density, cy + 6 * density, paint)
        }
        if (st.seekable && (active || touch || st.bubble != null || st.live)) {
            paint.color = Color.WHITE
            canvas.drawCircle(px, cy, (if (active) 9f else 7f) * density, paint)
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val st = m ?: return false
        if (!touch || !st.seekable) return false
        val f = ((ev.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                onScrub(f, 0)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) onScrub(f, 1)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                onScrub(f, 2)
            }
        }
        invalidate()
        return true
    }
}
