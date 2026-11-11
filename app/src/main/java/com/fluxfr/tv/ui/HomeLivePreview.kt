package com.fluxfr.tv.ui

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.fluxfr.tv.data.Channel
import com.fluxfr.tv.data.Settings
import com.fluxfr.tv.player.PlayItem
import com.fluxfr.tv.player.PlayerSession
import com.fluxfr.tv.player.ProviderConnections
import com.fluxfr.tv.player.StartMode
import com.fluxfr.tv.player.Timeshift
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Aperçu en direct de l'accueil (TV) : UN seul lecteur à la fois, partagé par tout l'accueil.
 *
 * L'abonnement IPTV n'autorise souvent qu'UNE connexion : l'aperçu doit être fermé (et le fournisseur doit l'avoir
 * vu fermé) avant que le lecteur plein écran n'ouvre la sienne. D'où :
 *  - démarrage seulement après [REST_MS] d'arrêt sur une vignette en direct (décidé par l'accueil), arrêt au
 *    moindre changement de focus, quand l'accueil perd le focus, à l'arrêt de l'appli ou à l'ouverture d'une fiche ;
 *  - jamais deux démarrages à moins de [MIN_RESTART_MS] (protection anti-abus des fournisseurs) ;
 *  - [MAX_FAILURES] échecs d'affilée : aperçus désactivés jusqu'à la fin de la session ;
 *  - tout lancement du lecteur depuis l'accueil passe par [playAfterPreview] / [playFromHome] ([stopAndAwait]).
 *
 * Exclusivité avec la bande-annonce de l'accueil : l'accueil n'en demande qu'une à la fois et appelle [stop]
 * avant de lancer une bande-annonce. Toutes les méthodes s'appellent sur le thread principal.
 */
@OptIn(UnstableApi::class)
object HomeLivePreview {
    /** Temps d'arrêt du focus sur une vignette en direct avant de lancer l'aperçu. */
    const val REST_MS = 2_500L
    /** Intervalle minimal entre deux démarrages (fournisseurs : connexions répétées = abus). */
    const val MIN_RESTART_MS = 4_000L
    /** Échecs d'affilée avant de désactiver les aperçus pour la session. */
    const val MAX_FAILURES = 3
    /** Pas d'image au bout de ce délai : échec. */
    private const val FIRST_FRAME_TIMEOUT_MS = 12_000L
    /** Après release() : laisse au fournisseur le temps de voir la connexion fermée. */
    private const val RELEASE_GRACE_MS = 300L

    /** Aperçu en cours : chaîne, lecteur, première image affichée. */
    class Session internal constructor(val channelId: String, val player: ExoPlayer, val rendered: Boolean)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    private val scope = MainScope()
    private var player: ExoPlayer? = null
    private var timeoutJob: Job? = null
    private var lastStartAt = 0L
    private var lastReleaseAt = 0L
    /** Pas de démarrage avant cet instant (lecteur plein écran en cours d'ouverture). */
    private var holdUntil = 0L
    /** Incrémenté à chaque arrêt : un démarrage en attente (intervalle minimal) ne part plus. */
    private var generation = 0
    private var failures = 0

    /** Désactivé pour la session ([MAX_FAILURES] échecs d'affilée). */
    var disabled = false
        private set

    /** Un aperçu tourne, ou vient d'être libéré (la connexion n'est peut-être pas encore vue fermée). */
    fun isBusy(): Boolean =
        player != null || SystemClock.elapsedRealtime() - lastReleaseAt < RELEASE_GRACE_MS

    /**
     * Lance l'aperçu de [channel] (meilleure source, comme [com.fluxfr.tv.player.Playables.channels]) en respectant
     * l'intervalle minimal ; à appeler APRÈS le temps d'arrêt. Annulable : la coroutine annulée pendant l'attente
     * ne démarre rien. Déjà en cours sur cette chaîne : rien.
     */
    suspend fun play(ctx: Context, channel: Channel) {
        if (disabled) return
        val url = channel.sources.firstOrNull()?.url?.takeIf { it.isNotBlank() } ?: return
        if (_session.value?.channelId == channel.id) return
        stop()
        val gen = generation
        val now = SystemClock.elapsedRealtime()
        val wait = maxOf(lastStartAt + MIN_RESTART_MS - now, holdUntil - now, lastReleaseAt + RELEASE_GRACE_MS - now)
        if (wait > 0) delay(wait)
        if (gen != generation || disabled || SystemClock.elapsedRealtime() < holdUntil) return
        // Portier des connexions du lecteur ([ProviderConnections]) : abonnement à une seule connexion (ou nombre
        // inconnu) → on attend que le lecteur principal (flux, enregistreur du direct) ait fermé les siennes ;
        // toujours occupé après [ProviderConnections.MAX_WAIT_MS] : pas d'aperçu (ce n'est pas un échec).
        if (!awaitProviderFree()) return
        if (gen != generation || disabled || SystemClock.elapsedRealtime() < holdUntil) return
        lastStartAt = SystemClock.elapsedRealtime()
        val p = runCatching { build(ctx.applicationContext, url) }.getOrNull()
        if (p == null) { fail(); return }
        player = p
        // Connexion d'aperçu déclarée au portier : l'enregistreur du direct du lecteur principal attend sa fermeture.
        ProviderConnections.playerOpened()
        gateCounted = true
        _session.value = Session(channel.id, p, rendered = false)
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (player !== p) return
                failures = 0
                timeoutJob?.cancel()
                val s = _session.value
                if (s != null && s.player === p && !s.rendered) _session.value = Session(s.channelId, p, rendered = true)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player === p) fail()
            }
        })
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(FIRST_FRAME_TIMEOUT_MS)
            if (player === p && _session.value?.rendered != true) fail()
        }
    }

    private fun fail() {
        failures++
        if (failures >= MAX_FAILURES) disabled = true
        stop()
    }

    /** Arrête et libère tout de suite (changement de focus, accueil quitté, appli arrêtée). */
    fun stop() {
        generation++
        releaseNow()
    }

    /** true si un lecteur a été libéré. */
    private fun releaseNow(): Boolean {
        timeoutJob?.cancel()
        timeoutJob = null
        val p = player ?: return false
        player = null
        _session.value = null
        runCatching { p.stop() }
        runCatching { p.release() }
        lastReleaseAt = SystemClock.elapsedRealtime()
        // Fermeture signalée au portier : le lecteur principal peut ouvrir sa connexion.
        if (gateCounted) {
            gateCounted = false
            ProviderConnections.playerClosed()
        }
        return true
    }

    /** La connexion d'aperçu est comptée chez le portier ([ProviderConnections]). */
    private var gateCounted = false

    /** Le lecteur principal n'a plus de connexion ouverte au fournisseur (flux ou enregistreur du direct). */
    private fun providerFree(): Boolean {
        if (!ProviderConnections.singleConnection) return true
        if (ProviderConnections.playerConnections > 0) return false
        val r = Timeshift.lastRecorderOrNull()
        return r == null || !r.isAlive
    }

    /** Attend (au plus [ProviderConnections.MAX_WAIT_MS]) que le fournisseur soit libre ; false sinon. */
    private suspend fun awaitProviderFree(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + ProviderConnections.MAX_WAIT_MS
        while (!providerFree()) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(100)
        }
        return true
    }

    /**
     * Arrête l'aperçu et attend que la connexion soit libérée (release() puis ~300 ms pour que le fournisseur
     * la voie fermée), [timeoutMs] au plus. À appeler AVANT d'ouvrir le lecteur plein écran.
     */
    suspend fun stopAndAwait(timeoutMs: Long = 1_500L) {
        stop()
        val remaining = lastReleaseAt + RELEASE_GRACE_MS - SystemClock.elapsedRealtime()
        if (remaining > 0) withTimeoutOrNull(timeoutMs) { delay(remaining) }
    }

    /**
     * Exécute [action] (ouverture du lecteur) après l'arrêt complet de l'aperçu : tout de suite si aucun
     * aperçu ne tourne, sinon après [stopAndAwait]. Aucun aperçu ne redémarre pendant l'ouverture du lecteur.
     */
    fun playAfterPreview(action: () -> Unit) {
        holdUntil = SystemClock.elapsedRealtime() + 3_000L
        if (!isBusy()) {
            stop()
            action()
            return
        }
        scope.launch {
            stopAndAwait()
            action()
        }
    }

    private fun build(ctx: Context, url: String): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Settings(ctx).userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val selector = DefaultTrackSelector(ctx).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguages("fr", "fre", "fra")
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            )
        }
        return ExoPlayer.Builder(
            ctx,
            // Comme le lecteur : décodeur FFmpeg (AC3, E-AC3…) en secours, sinon pas de son sur certaines box.
            DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
        )
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
            // Direct : petit tampon (démarrage rapide, peu de données téléchargées pour un aperçu).
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 5_000, 500, 1_000).build())
            .build().apply {
                volume = 1f
                setMediaItem(
                    MediaItem.Builder().setUri(url).apply {
                        if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
                    }.build()
                )
                playWhenReady = true
                prepare()
            }
    }
}

/**
 * Ouvre le lecteur depuis l'accueil : TOUJOURS par ici (jamais PlayerSession.start directement), pour que
 * l'aperçu en direct soit fermé avant (abonnements à une seule connexion).
 */
fun playFromHome(ctx: Context, queue: List<PlayItem>, index: Int, startMode: StartMode = StartMode.ASK) {
    HomeLivePreview.playAfterPreview { PlayerSession.start(ctx, queue, index, startMode) }
}

/** Vidéo de l'aperçu en direct de [channelId] (rien si l'aperçu en cours est une autre chaîne). Fondu à la 1re image. */
@OptIn(UnstableApi::class)
@Composable
fun HomeLiveVideo(channelId: String, modifier: Modifier = Modifier) {
    val s by HomeLivePreview.session.collectAsState()
    val session = s?.takeIf { it.channelId == channelId } ?: return
    val alpha by animateFloatAsState(if (session.rendered) 1f else 0f, tween(700), label = "liveVideo")
    val p = session.player
    Box(modifier.focusProperties { canFocus = false }.alpha(alpha)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    keepScreenOn = true
                }
            },
            update = { if (it.player !== p) it.player = p },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
        )
    }
}
