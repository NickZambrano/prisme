package com.fluxfr.tv.player

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import coil.dispose
import coil.load
import com.fluxfr.tv.data.EpgProgram
import com.fluxfr.tv.player.PlayerUi.ACCENT
import com.fluxfr.tv.player.PlayerUi.CARD
import com.fluxfr.tv.player.PlayerUi.SECONDARY
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * À droite de la liste des chaînes : aperçu vidéo muet de la chaîne sélectionnée (abonnement à
 * 2 connexions ou plus), ou à défaut une fiche « programme en cours / à suivre ».
 */
@OptIn(UnstableApi::class)
class ChannelPreview(
    private val ctx: Context,
    root: FrameLayout,
    leftPx: Int,
    topPx: Int,
    private val http: DataSource.Factory,
) {
    private fun dp(v: Int) = PlayerUi.dp(ctx, v)
    private val hourFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val width = (ctx.resources.displayMetrics.widthPixels * 0.34f).toInt()

    // --- Aperçu vidéo ---
    private val videoBox: FrameLayout
    private val texture: TextureView
    private val videoLogo: ImageView
    private val videoStatus: TextView
    private val videoCaption: TextView
    private var player: ExoPlayer? = null

    // --- Fiche programme ---
    private val infoBox: LinearLayout
    private val infoLogo: ImageView
    private val infoName: TextView
    private val infoTitle: TextView
    private val infoTime: TextView
    private val infoBar: LinearLayout
    private val infoFill: View
    private val infoRest: View
    private val infoDesc: TextView
    private val infoNext: TextView
    private var infoLogoUrl: String? = null

    /** Chaîne actuellement décrite (aperçu ou fiche). */
    var shownId: String? = null
        private set
    val infoShown: Boolean get() = infoBox.visibility == View.VISIBLE

    init {
        val radius = dp(16).toFloat()
        texture = TextureView(ctx)
        videoLogo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        videoStatus = PlayerUi.text(ctx, 13f, SECONDARY).apply { gravity = Gravity.CENTER }
        videoCaption = PlayerUi.text(ctx, 14f, Color.WHITE, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14), dp(18), dp(14), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        videoBox = FrameLayout(ctx).apply {
            background = PlayerUi.rounded(ctx, Color.BLACK, 16f)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
            visibility = View.GONE
            addView(texture, FrameLayout.LayoutParams(-1, -1))
            addView(videoLogo, FrameLayout.LayoutParams(dp(120), dp(72), Gravity.CENTER))
            addView(videoStatus, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = dp(60) })
            addView(videoCaption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        root.addView(videoBox, FrameLayout.LayoutParams(width, width * 9 / 16, Gravity.TOP or Gravity.START).apply {
            leftMargin = leftPx; topMargin = topPx
        })

        infoLogo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        infoName = PlayerUi.text(ctx, 17f, Color.WHITE, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(infoLogo, LinearLayout.LayoutParams(dp(64), dp(40)))
            addView(infoName, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        }
        infoTitle = PlayerUi.text(ctx, 18f, Color.WHITE, bold = true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(14), 0, 0)
        }
        infoTime = PlayerUi.text(ctx, 13f, SECONDARY).apply { setPadding(0, dp(6), 0, 0) }
        infoFill = View(ctx).apply { background = PlayerUi.rounded(ctx, ACCENT, 2f) }
        infoRest = View(ctx)
        infoBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = PlayerUi.rounded(ctx, 0x33FFFFFF, 2f)
            weightSum = 1000f
            addView(infoFill, LinearLayout.LayoutParams(0, -1, 0f))
            addView(infoRest, LinearLayout.LayoutParams(0, -1, 1000f))
        }
        infoDesc = PlayerUi.text(ctx, 14f, 0xFFDDE1EA.toInt()).apply {
            maxLines = 6; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(12), 0, 0)
        }
        infoNext = PlayerUi.text(ctx, 13f, SECONDARY).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(12), 0, 0)
        }
        infoBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = PlayerUi.rounded(ctx, CARD, 16f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            visibility = View.GONE
            addView(header)
            addView(infoTitle); addView(infoTime)
            addView(infoBar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8) })
            addView(infoDesc); addView(infoNext)
        }
        root.addView(infoBox, FrameLayout.LayoutParams(width, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = leftPx; topMargin = topPx
        })
    }

    /** Aperçu vidéo muet (second lecteur, petit tampon, définition réduite). */
    fun showVideo(item: PlayItem, number: Int) {
        hideInfo()
        stopVideo()
        // Seconde connexion au fournisseur : seulement si l'abonnement l'autorise (cf. ProviderConnections).
        if (!ProviderConnections.allowsExtra()) return
        val source = item.sources.firstOrNull() ?: return
        shownId = item.itemId
        videoCaption.text = "$number. ${item.title}"
        videoStatus.text = ""
        val logo = item.poster?.takeIf { it.isNotBlank() }
        if (logo != null) videoLogo.load(logo) { crossfade(false) } else { videoLogo.dispose(); videoLogo.setImageDrawable(null) }
        videoLogo.visibility = View.VISIBLE
        val selector = DefaultTrackSelector(ctx).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSizeSd()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            )
        }
        val p = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1_500, 4_000, 500, 1_000)
                    .build()
            )
            .build()
        p.volume = 0f
        p.setVideoTextureView(texture)
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { videoLogo.visibility = View.GONE; videoStatus.text = "" }
            override fun onPlayerError(error: PlaybackException) {
                videoLogo.visibility = View.VISIBLE
                videoStatus.text = "Aperçu indisponible"
            }
        })
        val b = MediaItem.Builder().setUri(source.url)
        if (source.url.contains(".m3u8", ignoreCase = true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(b.build())
        p.prepare()
        p.playWhenReady = true
        player = p
        videoBox.animate().cancel()
        videoBox.alpha = 0f
        videoBox.visibility = View.VISIBLE
        videoBox.animate().alpha(1f).setDuration(200).start()
    }

    /** Fiche programme (abonnement à une seule connexion ou nombre inconnu). */
    fun showInfo(item: PlayItem, number: Int, programs: List<EpgProgram>) {
        stopVideo()
        shownId = item.itemId
        infoName.text = "$number. ${item.title}"
        val logo = item.poster?.takeIf { it.isNotBlank() }
        if (logo != infoLogoUrl) {
            infoLogoUrl = logo
            if (logo == null) { infoLogo.dispose(); infoLogo.setImageDrawable(null) }
            else infoLogo.load(logo) { crossfade(false) }
        }
        infoLogo.visibility = if (logo == null) View.GONE else View.VISIBLE
        val now = System.currentTimeMillis()
        val cur = programs.firstOrNull { it.isNow(now) }
        val next = programs.firstOrNull { it !== cur && it.startMs > now }
        if (cur != null) {
            infoTitle.text = cur.title
            infoTime.text = "${hourFormat.format(Date(cur.startMs))} – ${hourFormat.format(Date(cur.endMs))}"
            val p = (cur.progress(now) * 1000).toInt().coerceIn(0, 1000)
            (infoFill.layoutParams as LinearLayout.LayoutParams).weight = p.toFloat()
            (infoRest.layoutParams as LinearLayout.LayoutParams).weight = (1000 - p).toFloat()
            infoBar.requestLayout()
            infoTime.visibility = View.VISIBLE
            infoBar.visibility = View.VISIBLE
            val desc = cur.description?.trim()?.takeIf { it.isNotEmpty() }
            infoDesc.text = desc ?: ""
            infoDesc.visibility = if (desc == null) View.GONE else View.VISIBLE
        } else {
            infoTitle.text = "Programme non disponible"
            infoTime.visibility = View.GONE
            infoBar.visibility = View.GONE
            infoDesc.visibility = View.GONE
        }
        if (next != null) infoNext.text = "À suivre : ${hourFormat.format(Date(next.startMs))} ${next.title}"
        infoNext.visibility = if (next != null) View.VISIBLE else View.GONE
        if (infoBox.visibility != View.VISIBLE) {
            infoBox.animate().cancel()
            infoBox.alpha = 0f
            infoBox.visibility = View.VISIBLE
            infoBox.animate().alpha(1f).setDuration(150).start()
        }
    }

    fun hide() {
        stopVideo()
        hideInfo()
        shownId = null
    }

    private fun hideInfo() {
        infoBox.animate().cancel()
        infoBox.visibility = View.GONE
    }

    /** Arrête et libère le second lecteur (la connexion est fermée tout de suite). */
    private fun stopVideo() {
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.clearVideoTextureView(texture) }
            runCatching { p.release() }
        }
        player = null
        videoBox.animate().cancel()
        videoBox.visibility = View.GONE
    }

    fun release() = hide()
}
