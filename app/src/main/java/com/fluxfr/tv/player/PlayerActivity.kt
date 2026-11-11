package com.fluxfr.tv.player

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import coil.dispose
import coil.load
import com.fluxfr.tv.data.Epg
import com.fluxfr.tv.data.EpgProgram
import com.fluxfr.tv.data.Progress
import com.fluxfr.tv.data.ProgressStore
import com.fluxfr.tv.data.Quality
import com.fluxfr.tv.data.RecentChannels
import com.fluxfr.tv.data.Settings
import com.fluxfr.tv.data.XtreamClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var store: ProgressStore
    private val handler = Handler(Looper.getMainLooper())

    // --- Habillage ---
    /** Commandes unifiées (titre, barre de progression, boutons) : VOD, séries et direct. */
    private lateinit var overlay: ControlOverlay
    private lateinit var pill: TextView
    private lateinit var digitsView: TextView
    /** Panneau centré (reprise, erreur). */
    private lateinit var panel: LinearLayout
    /** Panneau latéral droit (audio et sous-titres, qualité et image). */
    private lateinit var sidePanel: LinearLayout
    private var panelKind = PanelKind.NONE

    private val sidePanelOpen: Boolean get() = panelKind == PanelKind.TRACKS || panelKind == PanelKind.QUALITY
    private var audioCheckedFor: String? = null

    /** Où placer le focus en affichant les commandes (TV). */
    private enum class OverlayFocus { KEEP, PLAY, BAR }
    private val hideOverlayRunnable = Runnable { hideOverlay() }
    private val overlayTicker = object : Runnable {
        override fun run() {
            if (!::overlay.isInitialized || !overlay.visible) return
            refreshOverlay()
            handler.postDelayed(this, 500)
        }
    }
    /** Chaîne affichée en aperçu pendant un zapping CH+ / CH- (en-tête seul). */
    private var zapPreviewIndex: Int? = null
    /** Afficher les commandes au premier STATE_READY après un lancement / zapping. */
    private var overlayOnReady = false
    /** Glissement en cours sur la barre (tactile). */
    private var scrubbing = false

    // --- Aperçus pendant un déplacement ---
    private lateinit var thumbs: SeekThumbnails
    /** Dernière cible (tranche de 10 s) pour laquelle un aperçu a été demandé. */
    private var thumbAskedFor: Long = Long.MIN_VALUE

    // --- Guide TV (direct) ---
    private val epgExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val hourFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val hmsFormat = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    // --- Pistes audio / sous-titres ---
    private val trackPrefs by lazy { getSharedPreferences(TRACK_PREFS, MODE_PRIVATE) }
    /** Préférences mémorisées déjà appliquées pour cet élément. */
    private var tracksAppliedFor: String? = null
    private var trackHintDone = false
    private var listHintDone = false

    // --- Liste des chaînes (direct, ▲ / ▼) ---
    private class ChRow(
        val root: LinearLayout, val num: TextView, val logo: ImageView, val name: TextView,
        val prog: TextView, val bar: LinearLayout, val fill: View, val rest: View,
    ) { var logoUrl: String? = null }
    private lateinit var chList: LinearLayout
    private lateinit var chCount: TextView
    private val chRows = ArrayList<ChRow>()
    private var chListWidth = 0
    @Volatile private var chListOpen = false
    /** Ligne sélectionnée (index dans la file) et première ligne affichée. */
    private var chSel = 0
    private var chTop = 0
    @Volatile private var chVisibleIds: Set<String> = emptySet()
    /** Chaînes dont le guide a déjà été demandé depuis l'ouverture de la liste. */
    private val chEpgRequested = HashSet<String>()

    /** Chaîne déjà enregistrée dans les « récentes » pour ce lancement. */
    private var recordedFor: String? = null


    // --- État de lecture ---
    private var sourceIndex = 0
    /** Nombre de sources essayées sans succès depuis la dernière lecture réussie. */
    private var autoFailures = 0
    /** Nouvelles tentatives sur la même source. */
    private var sameSourceRetries = 0
    private var retryPositionMs = 0L
    private val liveRestarts = ArrayList<Long>()
    private var lastError: PlaybackException? = null

    // --- Touches ---
    /** Touches dont on a consommé l'ACTION_DOWN : on consomme aussi l'ACTION_UP. */
    private val consumedKeys = HashSet<Int>()

    // Zapping (live)
    private var pendingZapIndex: Int? = null
    private var lastZapStepAt = 0L
    private var previousChannelIndex: Int? = null
    private var digitBuffer = ""

    // Avance / retour rapide (VOD)
    private var seekTarget: Long? = null
    private var seekDelta = 0L
    private var lastSeekStepAt = 0L

    /** Élément lu : le replay lancé depuis le direct s'il y en a un, sinon l'élément de la file. */
    private val current: PlayItem? get() = catchupOverride ?: PlayerSession.queue.getOrNull(PlayerSession.index)

    // --- Contrôle du direct (pause, retour en arrière, retour au direct) ---
    /** Enregistrement local du direct en cours (une seule connexion au fournisseur). */
    private var ts: TimeshiftSession? = null
    /** La lecture en cours passe par le tampon local. */
    private var tsActive = false
    /** Position (octets) du tampon où la lecture actuelle a démarré, et heure d'arrivée correspondante. */
    private var tsBaseOffset = 0L
    private var tsBaseWall = -1L
    /** Heure de reprise après une erreur de lecture du tampon (0 : direct). */
    private var tsRetryWall = 0L
    /** Sources dont l'enregistrement est impossible : lues en direct, sans contrôle. */
    private val tsDisabledUrls = HashSet<String>()
    private var tsSpaceWarned = false
    /** Saut en préparation : retard visé sur le direct (ms) et saut cumulé (positif = vers l'avant). */
    private var liveSeekBehind: Long? = null
    private var liveSeekDelta = 0L
    private var lastLiveSeekAt = 0L
    /** ▶ maintenu : le retour au direct a déjà été fait pour cet appui. */
    private var goLiveHeld = false
    /** Replay (fournisseur) lancé depuis le direct : ⏭ ou Retour reviennent au direct. */
    private var catchupOverride: PlayItem? = null
    private val commitLiveSeek = Runnable { applyLiveSeek() }
    private val stopRecordingRunnable = Runnable { ts?.stopRecording() }

    // Carte « Épisode suivant » (séries)
    private lateinit var nextCard: LinearLayout
    private lateinit var nextCardSubtitle: TextView
    private lateinit var nextCardCountdown: TextView
    private lateinit var nextCardThumb: ImageView
    private var nextCardVisible = false
    /** La carte « Épisode suivant » a été fermée (Retour) pour cet épisode. */
    private var nextCardDismissed = false

    // --- Zapping rapide (un seul lecteur réutilisé, tampon réduit en direct) ---
    private lateinit var loadControl: ZapLoadControl
    private lateinit var httpFactory: DefaultHttpDataSource.Factory
    private lateinit var zapOverlay: FrameLayout
    private lateinit var zapLogo: ImageView
    private lateinit var zapName: TextView
    /** Début du changement de chaîne (elapsedRealtime) ; 0 si aucune mesure en cours. */
    private var zapStartAt = 0L
    private var zapFirstFrameLogged = false
    private val hideZapOverlayRunnable = Runnable { hideZapOverlay() }

    // --- Préférences image / son ---
    private val appPrefs by lazy { getSharedPreferences(APP_PREFS, MODE_PRIVATE) }
    private val afrEnabled: Boolean get() = appPrefs.getBoolean(KEY_AFR, true)
    private val nightEnabled: Boolean get() = appPrefs.getBoolean(KEY_NIGHT, false)
    private lateinit var afr: AutoFrameRate
    private val night = NightMode()

    // --- Écran de pause (VOD) ---
    private lateinit var pauseScreen: PauseScreen
    private val infoExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private class VodExtra(
        val plot: String?, val year: String?, val cast: String?, val durationSecs: Long, val episodeTitle: String?,
    )
    private val vodExtras = HashMap<String, VodExtra>()
    private val vodExtraRequested = HashSet<String>()
    private val showPauseRunnable = Runnable { maybeShowPauseScreen() }

    // --- Aperçu de chaîne (liste ▲ / ▼) ---
    private lateinit var preview: ChannelPreview
    /** Connexions simultanées autorisées par l'abonnement (-1 : inconnu). */
    private var maxConnections = -1
    /** Ligne pour laquelle l'aperçu a été programmé (-1 : aucune). */
    private var previewSel = -1
    private val previewRunnable = Runnable { showChannelPreview() }

    // --- Téléphone / tablette (tactile) : rien de tout ceci n'est actif sur TV ---
    /** false sur TV (télécommande, comportement d'origine), true sur téléphone / tablette. */
    private val touch by lazy { !com.fluxfr.tv.ui.FormFactor.isTv(this) }
    /** Geste vertical en cours : 1 = luminosité (moitié gauche), 2 = volume (moitié droite). */
    private var swipeMode = 0
    private var swipeStartValue = 0f

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    private fun dpf(v: Float): Float = v * density

    private val periodicSave = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 10_000)
        }
    }
    /** Rafraîchit le guide TV de la chaîne en cours toutes les 60 s. */
    private val epgTicker = object : Runnable {
        override fun run() {
            val item = current
            if (item != null && item.live) {
                requestEpg(item.itemId)
                handler.postDelayed(this, 60_000)
            }
        }
    }
    private val hidePill = Runnable { pill.visibility = View.GONE }
    private val commitZap = Runnable {
        val target = pendingZapIndex ?: return@Runnable
        pendingZapIndex = null
        goToChannel(target)
    }
    private val hideChannelList = Runnable { closeChannelList() }
    /** Guide des lignes visibles de la liste des chaînes (en arrière-plan, une fois par ouverture). */
    private val chEpgFetch = Runnable {
        if (!chListOpen) return@Runnable
        val ctx = applicationContext
        val now = System.currentTimeMillis()
        for (id in chVisibleIds) {
            if (id.isBlank() || id in chEpgRequested) continue
            if (Epg.cached(id).any { it.isNow(now) }) continue
            chEpgRequested += id
            runCatching {
                epgExecutor.execute {
                    // Ligne sortie de l'écran entre-temps (défilement rapide) : on la redemandera plus tard.
                    if (!chListOpen || id !in chVisibleIds) {
                        handler.post { chEpgRequested.remove(id) }
                        return@execute
                    }
                    runCatching { Epg.nowNext(ctx, id, 2) }
                    handler.post { if (!isDestroyed && chListOpen) renderChannelList() }
                }
            }
        }
    }
    private val commitDigits = Runnable { applyDigits() }
    private val commitSeek = Runnable { applySeek() }
    private val cardTicker = object : Runnable {
        override fun run() {
            updateNextCard()
            handler.postDelayed(this, 500)
        }
    }
    private val retryRunnable = Runnable {
        val item = current ?: return@Runnable
        // Direct enregistré : on reprend là où l'on était dans le tampon.
        val wall = tsRetryWall
        tsRetryWall = 0
        startSource(item, if (item.live) wall.coerceAtLeast(0) else retryPositionMs, autoPlay = retryAutoPlay())
    }

    /** Lecture automatique après une nouvelle tentative : jamais derrière « Reprendre ? », pause conservée. */
    private fun retryAutoPlay(): Boolean =
        PlaybackRules.retryAutoPlay(panelKind == PanelKind.RESUME, player.playWhenReady)

    // --- Progression (sauvegarde) ---
    /** Clé de l'élément dont la durée réelle est connue (STATE_READY vu avec une durée) ; null sinon. */
    private var durationKnownFor: String? = null
    /** Dernière position enregistrée (par clé) : pas de nouvelle écriture sans changement réel. */
    private var lastSavedKey: String? = null
    private var lastSavedPosition: Long? = null
    /** Numéro de la dernière sauvegarde : une écriture différée plus ancienne n'écrase jamais une plus récente. */
    private val saveSeq = java.util.concurrent.atomic.AtomicLong(0)
    /** Numéro de la dernière sauvegarde réellement écrite. */
    private val savedSeq = java.util.concurrent.atomic.AtomicLong(0)

    // --- Préférences de pistes par défaut (rétablies à chaque élément) ---
    private var defaultAudioLangs: List<String> = listOf("fr", "fre", "fra")
    private var defaultTextLangs: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PlayerSession.queue.isEmpty()) { finish(); return }
        store = ProgressStore.get(this)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Settings(this).userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val trackSelector = DefaultTrackSelector(this).apply {
            // Piste audio française en priorité quand le flux en propose plusieurs.
            setParameters(buildUponParameters().setPreferredAudioLanguages("fr", "fre", "fra"))
        }
        httpFactory = http
        // Un seul lecteur pour toute la session : les changements de chaîne réutilisent la même
        // instance (setMediaItem annule aussitôt le chargement précédent).
        loadControl = ZapLoadControl()
        // Direct MPEG-TS : images clés « non IDR » acceptées (reprise plus rapide après un saut dans le tampon).
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        Timeshift.cleanup(this)
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            // Décodeur audio FFmpeg (AC3, E-AC3, DTS, TrueHD…) en secours : certaines box (ex. Chromecast avec
            // Google TV) ne savent pas décoder le Dolby des flux IPTV → sinon aucune piste audio.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
            .setTrackSelector(trackSelector)
            // prisme-ts:// = tampon local du contrôle du direct ; file:// et content:// = fichiers de l'appareil,
            // prisme-smb:// = partages réseau (onglet « Fichiers ») ; le reste en HTTP.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(FilesRoutingDataSource.Factory(this, TimeshiftRoutingDataSource.Factory(http)), extractors)
                    .setLoadErrorHandlingPolicy(TimeshiftLoadErrorPolicy())
            )
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        afr = AutoFrameRate(this)
        // Le changement de mode d'affichage est fait ici : pas de second changement par ExoPlayer.
        if (afrEnabled) player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        night.enabled = nightEnabled
        // Langues par défaut (audio FR, sous-titres selon les réglages d'accessibilité) : rétablies à chaque élément.
        player.trackSelectionParameters.let { p ->
            defaultAudioLangs = p.preferredAudioLanguages.toList()
            defaultTextLangs = p.preferredTextLanguages.toList()
        }
        maxConnections = AccountLimits.cached(this)
        ProviderConnections.maxConnections = maxConnections
        AccountLimits.refresh(this, infoExecutor) { n ->
            ProviderConnections.maxConnections = n
            handler.post { maxConnections = n }
        }

        playerView = PlayerView(this).apply {
            player = this@PlayerActivity.player
            // Commandes maison (ControlOverlay) : aucun contrôleur Media3 (ni engrenage, ni bouton sous-titres).
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            keepScreenOn = false
            setBackgroundColor(Color.BLACK)
        }

        val root = FrameLayout(this)
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        buildZapOverlay(root)
        pauseScreen = PauseScreen(this, root, touch)
        overlay = ControlOverlay(this, touch, onBack = { finish() }) { f, phase -> onBarScrub(f, phase) }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        buildPill(root)
        buildDigits(root)
        buildNextCard(root)
        buildChannelList(root)
        preview = ChannelPreview(this, root, dp(20) + chListWidth + dp(20), dp(20), http)
        buildPanel(root)
        setContentView(root)
        thumbs = SeekThumbnails(this, handler, Settings(this).userAgent, dp(240))
        if (touch) {
            enterImmersive()
            installTouchGestures()
        }

        player.addListener(object : Player.Listener {
            // Les vraies pistes audio ne sont connues qu'à la lecture : la piste FR est choisie
            // automatiquement (préférence), et on prévient si le film n'en a aucune.
            override fun onTracksChanged(tracks: Tracks) {
                val item = current ?: return
                val remembered = applyRememberedTracks(item, tracks)
                if (item.live || audioCheckedFor == item.itemId) return
                // Langue audio choisie explicitement pour cette série / ce film : pas d'avertissement.
                if (remembered) { audioCheckedFor = item.itemId; return }
                val langs = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).language } }
                if (langs.isEmpty()) return
                audioCheckedFor = item.itemId
                // « und », « mul »… : langue non renseignée, on ne peut rien en conclure.
                val known = langs.filterNotNull().map { it.lowercase().trim() }
                    .filter { it !in UNKNOWN_LANGS && !it.startsWith("und-") }
                if (known.isNotEmpty() && known.none { it.startsWith("fr") }) {
                    showPill("⚠ Pas de piste audio française détectée (" + known.distinct().joinToString(", ") + ")", 7_000)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                setKeepScreenOn(isPlaying)
                if (isPlaying) maybeShowTrackHint()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Écran de pause : 5 s après la mise en pause ; disparaît dès la reprise.
                if (playWhenReady) {
                    handler.removeCallbacks(showPauseRunnable)
                    if (pauseScreen.isShown) exitPauseScreen()
                } else armPauseScreen()
                // Pause : les commandes s'affichent et restent ; reprise : elles se masquent après 4 s.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) showOverlay()
                else if (overlay.visible) { refreshOverlay(); scheduleOverlayHide() }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (overlay.visible) refreshOverlay()
            }

            override fun onRenderedFirstFrame() {
                if (zapStartAt > 0 && !zapFirstFrameLogged) {
                    zapFirstFrameLogged = true
                    Log.i(ZAP_TAG, "Première image après ${SystemClock.elapsedRealtime() - zapStartAt} ms (${current?.title})")
                }
                hideZapOverlay()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                night.update(audioSessionId, player.audioFormat?.channelCount ?: 2)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    // Durée réelle connue : la progression peut désormais être enregistrée pour cet élément.
                    current?.progressKey?.let { key ->
                        val d = player.duration
                        if (!player.isCurrentMediaItemDynamic && d != C.TIME_UNSET && d > 0) durationKnownFor = key
                    }
                    // Reprise estimée : même pourcentage, sur la vraie durée du fichier (lecture SQLite hors fil principal).
                    exactResumeKey?.let { key ->
                        exactResumeKey = null
                        val d = player.duration
                        if (d > 0) {
                            val gen = exactResumeGen
                            runCatching {
                                infoExecutor.execute {
                                    val exact = runCatching { store.resumePosition(key, d) }.getOrDefault(0L)
                                    handler.post {
                                        // Autre élément, ou « Depuis le début » choisi entre-temps : rien à faire.
                                        if (isDestroyed || gen != exactResumeGen || current?.progressKey != key || exact <= 0) return@post
                                        if (kotlin.math.abs(exact - player.currentPosition) > 20_000) player.seekTo(exact)
                                    }
                                }
                            }
                        }
                    }
                    if (zapStartAt > 0) {
                        Log.i(ZAP_TAG, "Changement → STATE_READY en ${SystemClock.elapsedRealtime() - zapStartAt} ms (${current?.title})")
                        zapStartAt = 0
                    }
                    hideZapOverlay()
                    autoFailures = 0
                    sameSourceRetries = 0
                    lastError = null
                    if (panelKind == PanelKind.ERROR) hidePanel()
                    // Début de lecture : commandes 3 s (durée et barre maintenant connues).
                    if (overlayOnReady) {
                        overlayOnReady = false
                        if (player.playWhenReady) showOverlay(durationMs = START_OVERLAY_MS)
                    }
                    val item = current
                    if (item != null && item.live && recordedFor != item.itemId) {
                        recordedFor = item.itemId
                        RecentChannels.record(applicationContext, item.itemId)
                    }
                    maybeShowTrackHint()
                }
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                // Pause plus longue que le tampon : reprise au plus ancien moment encore enregistré.
                val s = ts
                if (tsActive && s != null && !s.released &&
                    generateSequence<Throwable>(error) { it.cause }.any { it is BehindWindowException }
                ) {
                    playFromBuffer(s.buffer.seekOffset(s.firstWall + 10_000), autoPlay = true)
                    showPill("Début du direct enregistré atteint", 3_000)
                    showOverlay()
                    return
                }
                handleError(error)
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                if (afrEnabled) afr.onVideoFormat(format)
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                night.update(player.audioSessionId, format.channelCount)
            }
        })
        night.update(player.audioSessionId, 2)
        playCurrent()
        handler.postDelayed(periodicSave, 10_000)
        handler.postDelayed(cardTicker, 500)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::player.isInitialized) return
        saveProgress()
        pendingZapIndex = null
        digitBuffer = ""
        digitsView.visibility = View.GONE
        closeChannelList(animate = false)
        playCurrent()
    }

    private fun setKeepScreenOn(on: Boolean) {
        playerView.keepScreenOn = on
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------------------------------------
    // Lecture
    // ---------------------------------------------------------------------------------------------

    private fun playCurrent(keepCatchup: Boolean = false) {
        if (!keepCatchup) catchupOverride = null
        val item = current ?: run { finish(); return }
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(commitSeek)
        cancelLiveSeek()
        thumbs.cancel()
        zapPreviewIndex = null
        tsRetryWall = 0
        // Autre chaîne (ou replay) : l'enregistrement du direct précédent est arrêté et effacé.
        ts?.let { if (!item.live || it.itemId != item.itemId) releaseTimeshift() }
        seekTarget = null
        sourceIndex = 0
        autoFailures = 0
        sameSourceRetries = 0
        liveRestarts.clear()
        hidePanel()
        hidePillNow()
        handler.removeCallbacks(showPauseRunnable)
        pauseScreen.hide()
        nextCardDismissed = false
        hideNextCard()
        requestVodExtra(item)
        recordedFor = null
        tracksAppliedFor = null
        durationKnownFor = null
        exactResumeGen++
        resetTrackSelection()
        // Lecture SQLite (une ligne, par clé) gardée ici : elle décide du panneau « Reprendre ? » avant la lecture.
        val saved = savedProgress(item)
        // Choix déjà fait sur la fiche (Reprendre / Depuis le début) : pas de second panneau « Reprendre ? ».
        val mode = PlayerSession.takeStartMode()
        val resume = if (mode == StartMode.FROM_START) 0L else resumePosition(saved)
        exactResumeKey = item.progressKey?.takeIf { resume > 0 && saved?.estimated == true }
        if (resume > 0 && mode == StartMode.ASK) {
            startSource(item, resume, autoPlay = false)
            showResumePrompt(resume)
        } else if (resume > 0) {
            startSource(item, resume)
            overlayOnReady = true
        } else {
            startSource(item, 0)
            // Changement de chaîne : titre et programme tout de suite ; VOD : au premier STATE_READY.
            if (item.live) showOverlay(durationMs = START_OVERLAY_MS)
            overlayOnReady = true
        }
        handler.removeCallbacks(epgTicker)
        if (item.live) {
            requestEpg(item.itemId)
            handler.postDelayed(epgTicker, 60_000)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Guide TV (direct)
    // ---------------------------------------------------------------------------------------------

    /** Récupère le guide en arrière-plan (mis en cache par [Epg]) ; les commandes affichées sont rafraîchies. */
    private fun requestEpg(id: String) {
        if (id.isBlank()) return
        val ctx = applicationContext
        runCatching {
            epgExecutor.execute {
                runCatching { Epg.nowNext(ctx, id, 2) }
                handler.post {
                    if (isDestroyed) return@post
                    if (overlay.visible) refreshOverlay()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Commandes unifiées (ControlOverlay)
    // ---------------------------------------------------------------------------------------------

    /**
     * Affiche les commandes complètes. [focus] (TV) : PLAY = bouton lecture / pause, BAR = barre de
     * progression, KEEP = focus actuel (⏯ si rien n'a le focus).
     */
    private fun showOverlay(focus: OverlayFocus = OverlayFocus.KEEP, durationMs: Long = OVERLAY_TIMEOUT_MS) {
        if (!::overlay.isInitialized || chListOpen || panelKind != PanelKind.NONE) return
        if (pauseScreen.isShown) pauseScreen.hide()
        zapPreviewIndex = null
        refreshOverlay()
        overlay.show(ControlOverlay.Mode.FULL)
        hideNextCard()
        if (!touch) {
            when (focus) {
                OverlayFocus.BAR -> if (!overlay.focusBar()) overlay.focusPlayPause()
                OverlayFocus.PLAY -> overlay.focusPlayPause()
                OverlayFocus.KEEP -> if (!overlay.hasFocusInside) overlay.focusPlayPause()
            }
        }
        scheduleOverlayHide(durationMs)
        handler.removeCallbacks(overlayTicker)
        handler.postDelayed(overlayTicker, 500)
        updatePillOffset()
    }

    /** En-tête seul (aperçu d'une chaîne pendant un zapping CH+ / CH-). */
    private fun showZapPreview(index: Int) {
        if (chListOpen || panelKind != PanelKind.NONE) return
        zapPreviewIndex = index
        refreshOverlay()
        overlay.show(ControlOverlay.Mode.HEADER)
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, 3_000)
        updatePillOffset()
    }

    private fun hideOverlay() {
        if (!::overlay.isInitialized) return
        handler.removeCallbacks(hideOverlayRunnable)
        handler.removeCallbacks(overlayTicker)
        zapPreviewIndex = null
        thumbs.cancel()
        thumbAskedFor = Long.MIN_VALUE
        overlay.hide()
        updatePillOffset()
        updateNextCard()
    }

    /** Les commandes restent affichées en pause, pendant un déplacement et sur l'écran de pause. */
    private fun overlayPinned(): Boolean =
        !player.playWhenReady || seekTarget != null || liveSeekBehind != null || scrubbing || pauseScreen.isShown

    private fun scheduleOverlayHide(durationMs: Long = OVERLAY_TIMEOUT_MS) {
        handler.removeCallbacks(hideOverlayRunnable)
        if (!overlay.visible || overlayPinned()) return
        handler.postDelayed(hideOverlayRunnable, durationMs)
    }

    /** Met à jour titre, barre, temps, badge et boutons à partir de l'état de lecture. */
    private fun refreshOverlay() {
        val zapIdx = zapPreviewIndex
        if (zapIdx != null) {
            val target = PlayerSession.queue.getOrNull(zapIdx) ?: return
            val (t, sub, meta) = headerFor(target, zapIdx, preview = true)
            overlay.setHeader(t, sub, meta)
            return
        }
        val item = current ?: return
        val (t, sub, meta) = headerFor(item, PlayerSession.index)
        overlay.setHeader(t, sub, meta)
        overlay.setPlaying(player.playWhenReady)
        val model = barModel(item)
        overlay.bindBar(model)
        overlay.setBadge(qualityBadge(item))
        overlay.setButtons(buttonsFor(item), RIGHT_BUTTONS)
        updateSeekThumbnail(item, model)
    }

    /** Titre, ligne secondaire (épisode / programme) et métadonnées atténuées. */
    private fun headerFor(item: PlayItem, index: Int, preview: Boolean = false): Triple<String, String?, String?> {
        if (item.live) {
            val title = "${item.number ?: (index + 1)} · ${item.title}"
            val now = System.currentTimeMillis()
            val at = if (preview) now else liveState(null)?.playWall ?: now
            val list = Epg.cached(item.itemId)
            val cur = Epg.cachedAt(item.itemId, at) ?: list.firstOrNull { it.isNow(now) }
            val sub = cur?.let { "${it.title}  (${hourFormat.format(Date(it.startMs))}–${hourFormat.format(Date(it.endMs))})" }
            val next = list.firstOrNull { it !== cur && it.startMs >= (cur?.endMs ?: now) - 60_000 && it.startMs > at }
            val meta = listOfNotNull(
                next?.let { "À suivre : ${hourFormat.format(Date(it.startMs))} · ${it.title}" },
                if (item.sources.size > 1 && !preview) "Source ${sourceIndex + 1}/${item.sources.size}" else null,
            ).joinToString("   ·   ").ifEmpty { null }
            return Triple(title, sub ?: if (preview) null else "Programme non disponible", meta)
        }
        if (item.catchupOf != null) {
            val prog = item.subtitle?.removePrefix("⟲ ")?.trim()
            return Triple(item.title, prog, "Replay · Direct pour revenir à la chaîne")
        }
        val extra = vodExtras[item.itemId]
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: extra?.durationSecs?.takeIf { it > 0 }?.times(1000)
        val pos = seekTarget ?: player.currentPosition
        val endAt = durationMs?.let {
            "Fin à " + hourFormat.format(Date(System.currentTimeMillis() + (it - pos).coerceAtLeast(0)))
        }
        val meta = listOfNotNull(item.year ?: extra?.year, durationMs?.let { PlayerTime.runtime(it) }, endAt)
            .joinToString("  ·  ").ifEmpty { null }
        return Triple(item.title, episodeLine(item), meta)
    }

    /** « S2 · É5 · Titre de l'épisode » (séries) ; sous-titre d'origine sinon. */
    private fun episodeLine(item: PlayItem): String? {
        if (item.type == Progress.EPISODE && (item.season > 0 || item.episode > 0)) {
            val name = cleanEpisodeTitle(item.episodeTitle ?: vodExtras[item.itemId]?.episodeTitle, item.title)
            return "S${item.season} · É${item.episode}" + (name?.let { " · $it" } ?: "")
        }
        return item.subtitle?.takeIf { it.isNotBlank() && item.type != Progress.MOVIE }
    }

    /** Barre de progression : VOD, direct enregistré (contrôle du direct) ou progression du programme. */
    private fun barModel(item: PlayItem): BarModel? {
        if (item.live) {
            val st = liveState()
            if (st != null) {
                // Échelle stable : le programme regardé (guide), sinon la fenêtre enregistrée arrondie à 30 min.
                val (start, end) = PlaybackRules.liveBarRange(st.programStart, st.programEnd, st.windowStartWall, st.liveWall)
                val span = (end - start).coerceAtLeast(1).toFloat()
                fun f(w: Long) = ((w - start) / span).coerceIn(0f, 1f)
                val pending = st.pendingDeltaMs != null || scrubbing
                // Pendant un déplacement : l'heure visée une seule fois (bulle), le retard à droite.
                val bubble = if (pending) {
                    if (st.atLive) "Direct" else hmsFormat.format(Date(st.playWall))
                } else null
                return BarModel(
                    showTrack = true, fraction = f(st.playWall), buffered = f(st.liveWall), ticks = FloatArray(0),
                    seekable = true,
                    leftText = hourFormat.format(Date(start)),
                    rightText = if (st.atLive) null else PlayerTime.remaining(st.behindMs),
                    livePill = st.atLive, bubble = bubble,
                    live = true, windowStart = f(st.windowStartWall),
                    liveAt = if (st.liveWall <= end) f(st.liveWall) else -1f,
                )
            }
            // Sans contrôle du direct : progression du programme en cours (guide).
            val now = System.currentTimeMillis()
            val cur = Epg.cached(item.itemId).firstOrNull { it.isNow(now) }
                ?: return BarModel(false, 0f, 0f, FloatArray(0), false, "", null, true, null)
            return BarModel(
                showTrack = true, fraction = cur.progress(now), buffered = 0f, ticks = FloatArray(0), seekable = false,
                leftText = hourFormat.format(Date(cur.startMs)), rightText = hourFormat.format(Date(cur.endMs)),
                livePill = true, bubble = null,
            )
        }
        val d = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val pos = (seekTarget ?: player.currentPosition).coerceAtLeast(0)
        if (d == null) {
            return BarModel(false, 0f, 0f, FloatArray(0), false, PlayerTime.clock(pos), null, false, null)
        }
        val seeking = seekTarget != null || scrubbing
        // Pendant un déplacement : la cible une seule fois (bulle) et la durée totale ; sinon « écoulé / total ».
        val left: CharSequence = if (seeking) {
            android.text.SpannableStringBuilder("Durée  " + PlayerTime.clock(d)).apply {
                setSpan(android.text.style.ForegroundColorSpan(PlayerUi.SECONDARY), 0, 5, 0)
            }
        } else android.text.SpannableStringBuilder(PlayerTime.clock(pos)).apply {
            val start = length
            append("  /  " + PlayerTime.clock(d))
            setSpan(android.text.style.ForegroundColorSpan(PlayerUi.SECONDARY), start, length, 0)
        }
        return BarModel(
            showTrack = true,
            fraction = (pos.toFloat() / d).coerceIn(0f, 1f),
            buffered = (player.bufferedPosition.toFloat() / d).coerceIn(0f, 1f),
            ticks = FloatArray(0),
            seekable = true,
            leftText = left,
            rightText = if (seeking) null else PlayerTime.remaining((d - pos).coerceAtLeast(0)),
            livePill = false,
            bubble = if (seeking) PlayerTime.clock(pos) else null,
        )
    }

    /** « 1080p · HDR · 5.1 » d'après le format lu ; direct : qualité annoncée de la source à défaut. */
    private fun qualityBadge(item: PlayItem): String? {
        val v = player.videoFormat
        val parts = ArrayList<String>()
        if (v != null && v.height > 0) {
            val h = v.height
            // Largeur prise en compte : un film « scope » 1920 × 800 reste du 1080p.
            parts += when {
                v.width >= 3200 || h >= 2000 -> "4K"
                h >= 1000 || v.width >= 1800 -> "1080p"
                h >= 700 || v.width >= 1200 -> "720p"
                else -> "SD"
            }
            val dv = v.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
            val transfer = v.colorInfo?.colorTransfer
            when {
                dv -> parts += "Dolby Vision"
                transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG -> parts += "HDR"
            }
        } else if (item.live) {
            item.sources.getOrNull(sourceIndex)?.quality?.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        }
        val a = player.audioFormat
        if (a != null) {
            when {
                a.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC -> parts += "Atmos"
                a.channelCount >= 8 -> parts += "7.1"
                a.channelCount == 6 -> parts += "5.1"
            }
        }
        return parts.joinToString(" · ").ifEmpty { null }
    }

    /** Boutons selon le contenu : ⏪ ⏯ ⏩ (si le déplacement est possible), puis actions à droite. */
    private fun buttonsFor(item: PlayItem): List<OverlayAction> {
        val list = ArrayList<OverlayAction>()
        val control = item.live && liveControl()
        if (!item.live || control) {
            list += OverlayAction("rew", PlayerIcon.REW10, "Reculer de 10 s", false) { buttonSeek(false) }
            list += OverlayAction("pp", PlayerIcon.PAUSE, "Lecture / pause", false) {
                if (item.live) liveTogglePause() else togglePlayPause()
            }
            list += OverlayAction("fwd", PlayerIcon.FWD10, "Avancer de 10 s", false) { buttonSeek(true) }
        }
        if (!item.live && item.type == Progress.EPISODE && PlayerSession.index + 1 < PlayerSession.queue.size) {
            list += OverlayAction("next", PlayerIcon.NEXT, "Épisode suivant", true) { playNextEpisode() }
        }
        if (item.live && restartableProgram(item) != null) {
            list += OverlayAction("restart", PlayerIcon.RESTART, "Début du programme", !touch) { restartProgram() }
        }
        val behind = control && liveState(null)?.atLive == false
        if (catchupOverride != null || behind) {
            list += OverlayAction("live", PlayerIcon.LIVE, "Direct", true) { goLive() }
        }
        if ((item.live || catchupOverride != null) && PlayerSession.queue.size > 1) {
            list += OverlayAction("channels", PlayerIcon.CHANNELS, "Chaînes", !touch) { openChannelList() }
        }
        list += OverlayAction("tracks", PlayerIcon.SUBTITLES, "Audio et sous-titres", !touch) { showTracksPanel() }
        list += OverlayAction("quality", PlayerIcon.QUALITY, "Qualité", !touch) { showQualityPanel() }
        return list
    }

    /** Boutons ⏪ / ⏩ : même saut que ◀ / ▶ (cumulé, appliqué après une courte pause). */
    private fun buttonSeek(forward: Boolean) {
        val item = current ?: return
        if (item.live) liveSeekStep(forward, 0, 10_000) else seekStep(forward, 0)
        scheduleOverlayHide()
    }

    /** Glissement sur la barre (tactile) : la cible suit le doigt, appliquée au relâchement. */
    private fun onBarScrub(f: Float, phase: Int) {
        val item = current ?: return
        scrubbing = phase != 2
        if (item.live) {
            val st = liveState(null) ?: return
            // Même échelle que la barre affichée ; seule la partie enregistrée est atteignable.
            val (start, end) = PlaybackRules.liveBarRange(st.programStart, st.programEnd, st.windowStartWall, st.liveWall)
            val wall = (start + ((end - start) * f).toLong()).coerceIn(st.windowStartWall, st.liveWall)
            val behind = (st.liveWall - wall).coerceAtLeast(0)
            if (liveSeekBehind == null) liveSeekDelta = 0
            liveSeekDelta += (liveSeekBehind ?: st.behindMs) - behind
            if (liveSeekDelta == 0L) liveSeekDelta = 1
            liveSeekBehind = behind
            handler.removeCallbacks(commitLiveSeek)
            if (phase == 2) applyLiveSeek()
        } else {
            val d = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
            seekTarget = (d * f).toLong().coerceIn(0, (d - 1_000).coerceAtLeast(0))
            handler.removeCallbacks(commitSeek)
            if (phase == 2) applySeek()
        }
        handler.removeCallbacks(hideOverlayRunnable)
        refreshOverlay()
        if (phase == 2) scheduleOverlayHide()
    }

    /**
     * Image d'aperçu au-dessus du curseur pendant un déplacement. Direct enregistré : tampon local.
     * VOD : seulement si l'abonnement autorise 2 connexions (sinon la seconde couperait la lecture).
     */
    private fun updateSeekThumbnail(item: PlayItem, model: BarModel?) {
        if (model?.bubble == null) {
            if (thumbAskedFor != Long.MIN_VALUE) { thumbs.cancel(); thumbAskedFor = Long.MIN_VALUE }
            return
        }
        if (item.live) {
            val s = ts ?: return
            if (!tsActive) return
            val st = liveState() ?: return
            val bucket = st.playWall / 10_000
            if (bucket == thumbAskedFor) return
            thumbAskedFor = bucket
            overlay.setPreviewImage(null)
            thumbs.requestLive(s, st.playWall) { bmp -> overlay.setPreviewImage(bmp) }
        } else {
            if (!ProviderConnections.allowsExtra(maxConnections)) return
            val url = item.sources.getOrNull(sourceIndex)?.url ?: return
            // Vignettes de déplacement : flux HTTP seulement (pas les fichiers locaux / partages réseau).
            if (!url.startsWith("http", ignoreCase = true)) return
            val target = seekTarget ?: return
            val bucket = target / 10_000
            if (bucket == thumbAskedFor) return
            thumbAskedFor = bucket
            overlay.setPreviewImage(null)
            thumbs.requestVod(url, target) { bmp -> overlay.setPreviewImage(bmp) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pistes audio et sous-titres
    // ---------------------------------------------------------------------------------------------

    /**
     * Nouvel élément : choix de pistes remis à zéro, y compris les langues préférées qu'un élément précédent a pu
     * imposer ([applyRememberedTracks]) ; on revient aux valeurs par défaut du lecteur (audio FR).
     */
    private fun resetTrackSelection() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredAudioLanguages(*defaultAudioLangs.toTypedArray())
            .setPreferredTextLanguages(*defaultTextLangs.toTypedArray())
            .build()
    }

    /** Clé de mémorisation : par série pour les épisodes, par film sinon ; null en direct. */
    private fun trackKey(item: PlayItem): String? = when {
        item.live -> null
        item.type == Progress.EPISODE && !item.seriesId.isNullOrBlank() -> "series:${item.seriesId}"
        else -> "item:${item.type}:${item.itemId}"
    }

    /** Applique les langues mémorisées (une fois par élément). Retourne true si une piste audio mémorisée a été appliquée. */
    private fun applyRememberedTracks(item: PlayItem, tracks: Tracks): Boolean {
        if (tracksAppliedFor == item.itemId) return false
        val key = trackKey(item) ?: return false
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty() && tracks.groups.none { it.type == C.TRACK_TYPE_TEXT }) return false
        tracksAppliedFor = item.itemId
        val audioLang = trackPrefs.getString("$key.audio", null)
        val textLang = trackPrefs.getString("$key.text", null)
        if (audioLang == null && textLang == null) return false
        val b = player.trackSelectionParameters.buildUpon()
        var audioApplied = false
        if (audioLang != null) {
            val match = findTrack(tracks, C.TRACK_TYPE_AUDIO) { sameLang(it.language, audioLang) }
            if (match != null) {
                b.setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
                audioApplied = true
            } else b.setPreferredAudioLanguages(audioLang, "fr", "fre", "fra")
        }
        when (textLang) {
            null -> {}
            TEXT_OFF -> b.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else -> {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                val match = findTrack(tracks, C.TRACK_TYPE_TEXT) { sameLang(it.language, textLang) }
                if (match != null) b.setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
                else b.setPreferredTextLanguage(textLang)
            }
        }
        player.trackSelectionParameters = b.build()
        return audioApplied
    }

    /** Code langue normalisé sur 2 lettres quand c'est possible (« fre », « fra », « fr-FR » → « fr »). */
    private fun canonicalLang(lang: String): String {
        var l = lang.lowercase().trim().substringBefore('-').substringBefore('_')
        BIBLIO_LANGS[l]?.let { l = it }
        if (l.length == 3) ISO3_TO_2[l]?.let { return it }
        return l
    }

    private fun findTrack(tracks: Tracks, type: Int, pred: (Format) -> Boolean): Pair<Tracks.Group, Int>? {
        for (g in tracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) if (g.isTrackSupported(i) && pred(g.getTrackFormat(i))) return g to i
        }
        return null
    }

    private fun isKnownLang(lang: String?): Boolean {
        val l = lang?.lowercase()?.trim() ?: return false
        return l !in UNKNOWN_LANGS && !l.startsWith("und-")
    }

    private fun sameLang(a: String?, b: String?): Boolean {
        if (!isKnownLang(a) || !isKnownLang(b)) return false
        return canonicalLang(a!!) == canonicalLang(b!!)
    }

    private fun langName(lang: String?): String? {
        if (!isKnownLang(lang)) return null
        val code = canonicalLang(lang!!)
        val name = runCatching { Locale(code).getDisplayLanguage(Locale.FRENCH) }.getOrNull()
        if (name.isNullOrBlank() || name.equals(code, ignoreCase = true)) return lang.uppercase()
        return name.replaceFirstChar { it.titlecase(Locale.FRENCH) }
    }

    /** Libellé sans détail technique : « Français (5.1) », « Anglais · VO », « Français (Atmos) ». */
    private fun audioLabel(f: Format, n: Int): String {
        val name = langName(f.language) ?: f.label?.trim()?.takeIf { it.isNotBlank() }?.let { if (it.length <= 3) langName(it) ?: it else it }
            ?: "Piste $n (langue inconnue)"
        val tag = f.label?.trim()?.takeIf {
            it.isNotBlank() && it.length <= 14 && !it.equals(name, ignoreCase = true) && langName(it) != name
        }
        val ch = when {
            f.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC -> "Atmos"
            f.channelCount >= 8 -> "7.1"
            f.channelCount == 6 -> "5.1"
            else -> null
        }
        return name + (tag?.let { " · $it" } ?: "") + (ch?.let { " ($it)" } ?: "")
    }

    private class TrackOption(val label: String, val selected: Boolean, val apply: () -> Unit)
    private class ToggleOption(val label: String, val isOn: () -> Boolean, val onToggle: (Boolean) -> Unit)

    /** Panneau « Audio et sous-titres » (MENU, ou bouton des commandes) : deux colonnes, une coche sur le choix en cours. */
    private fun showTracksPanel() {
        val item = current ?: return
        val tracks = player.currentTracks
        val key = trackKey(item)

        val audio = ArrayList<TrackOption>()
        var n = 0
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                val base = audioLabel(f, n)
                // Deux pistes identiques (VF + audiodescription souvent) : on les numérote pour les distinguer.
                val same = audio.count { it.label == base || it.label.startsWith("$base ·") }
                val label = if (same > 0) "$base · ${same + 1}" else base
                audio += TrackOption(label, g.isTrackSelected(i)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                    if (key != null && isKnownLang(f.language)) trackPrefs.edit().putString("$key.audio", f.language).apply()
                    showPill("Audio : $label", 2_500)
                }
            }
        }

        val subs = ArrayList<TrackOption>()
        var anyTextSelected = false
        n = 0
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                val selected = g.isTrackSelected(i)
                if (selected) anyTextSelected = true
                val name = langName(f.language) ?: f.label?.takeIf { it.isNotBlank() } ?: "Sous-titres $n"
                val forced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0
                val label = if (forced) "$name (forcés)" else name
                subs += TrackOption(label, selected) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                    if (key != null && isKnownLang(f.language)) trackPrefs.edit().putString("$key.text", f.language).apply()
                    showPill("Sous-titres : $label", 2_500)
                }
            }
        }
        val noTracks = audio.isEmpty() && subs.isEmpty()
        if (!noTracks) subs.add(0, TrackOption("Désactivés", !anyTextSelected) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            if (key != null) trackPrefs.edit().putString("$key.text", TEXT_OFF).apply()
            showPill("Sous-titres désactivés", 2_000)
        })
        val columns = ArrayList<Pair<String, List<TrackOption>>>()
        if (audio.isNotEmpty()) columns += "Audio" to audio
        if (!noTracks) columns += "Sous-titres" to subs
        openSidePanel(
            PanelKind.TRACKS, "Audio et sous-titres", columns, note = null,
            emptyText = "Aucune piste audio ou sous-titres disponible pour le moment",
        )
    }

    /** Panneau « Qualité » : sources / rendus vidéo, puis réglages « Image et son ». */
    private fun showQualityPanel() {
        val item = current ?: return
        val tracks = player.currentTracks
        // Qualité : sources de la chaîne (direct à plusieurs sources), sinon rendus vidéo du flux (HLS…).
        val quality = ArrayList<TrackOption>()
        var qualityOnly: String? = null
        if (item.sources.size > 1) {
            item.sources.forEachIndexed { i, s ->
                val q = s.quality.label.ifEmpty { "qualité ?" }
                quality += TrackOption("Source ${i + 1} · $q", i == sourceIndex) {
                    showPill("Source ${i + 1} · $q", 2_000)
                    if (i != sourceIndex) switchSource(i - sourceIndex)
                }
            }
        } else {
            val videoOverride = player.trackSelectionParameters.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
            val renditions = ArrayList<Triple<Tracks.Group, Int, Format>>()
            for (g in tracks.groups) {
                if (g.type != C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    val f = g.getTrackFormat(i)
                    if (f.height <= 0 || renditions.any { it.third.height == f.height }) continue
                    renditions += Triple(g, i, f)
                }
            }
            renditions.sortByDescending { it.third.height }
            if (renditions.size > 1) {
                quality += TrackOption("Auto", videoOverride == null) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                    showPill("Qualité : Auto", 2_000)
                }
                for ((g, i, f) in renditions) {
                    val label = "${f.height}p"
                    val sel = videoOverride != null && videoOverride.mediaTrackGroup == g.mediaTrackGroup && i in videoOverride.trackIndices
                    quality += TrackOption(label, sel) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                        showPill("Qualité : ${f.height}p", 2_000)
                    }
                }
            } else {
                val h = renditions.firstOrNull()?.third?.height ?: player.videoFormat?.height ?: 0
                val label = when {
                    h > 0 -> "${h}p"
                    else -> item.sources.firstOrNull()?.quality?.label?.ifEmpty { null }
                }
                qualityOnly = (label ?: "Qualité d'origine") + " · seule qualité disponible"
            }
        }
        openSidePanel(
            PanelKind.QUALITY, "Qualité",
            if (quality.isNotEmpty()) listOf("Qualité" to quality) else emptyList(),
            note = qualityOnly,
            emptyText = null,
            toggles = listOf(
                ToggleOption("Fréquence d'image auto", { afrEnabled }) { setAfrEnabled(it) },
                ToggleOption("Mode nuit", { nightEnabled }) { setNightEnabled(it) },
            ),
        )
    }

    /**
     * Panneau latéral droit (même style partout) : titre, colonnes de choix (coche sur l'option en cours,
     * fond blanc au focus), puis éventuellement une section « Image et son » à bascules.
     */
    private fun openSidePanel(
        kind: PanelKind,
        title: String,
        columns: List<Pair<String, List<TrackOption>>>,
        note: String?,
        emptyText: String?,
        toggles: List<ToggleOption> = emptyList(),
    ) {
        hideOverlay()
        closeChannelList(animate = false)
        panel.visibility = View.GONE
        sidePanel.removeAllViews()
        sidePanel.addView(PlayerUi.text(this, 22f, Color.WHITE, PlayerUi.Weight.SEMIBOLD).apply { text = title })
        val colWidth = dp(if (touch) 210 else 270)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val colViews = ArrayList<List<View>>()
        val targets = ArrayList<View?>()
        for ((header, options) in columns) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(PlayerUi.text(this, 14f, SECONDARY, PlayerUi.Weight.MEDIUM).apply {
                text = header; setPadding(dp(14), 0, 0, dp(8))
            })
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val views = options.map { opt -> optionRow(opt.label, opt.selected, colWidth) { hidePanel(); opt.apply() } }
            views.forEachIndexed { i, v ->
                v.nextFocusUpId = views.getOrNull(i - 1)?.id ?: v.id
                v.nextFocusDownId = views.getOrNull(i + 1)?.id ?: v.id
                list.addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(2) })
            }
            col.addView(ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isFocusable = false
                addView(list)
            }, LinearLayout.LayoutParams(-1, -2))
            // Largeur selon le contenu (bornée) : deux options n'occupent pas les deux tiers de l'écran.
            col.minimumWidth = dp(if (touch) 160 else 200)
            row.addView(col, LinearLayout.LayoutParams(-2, -2).apply { if (row.childCount > 0) leftMargin = dp(20) })
            colViews += views
            targets += options.indexOfFirst { it.selected }.let { views.getOrNull(it) } ?: views.firstOrNull()
        }
        colViews.forEachIndexed { c, views ->
            views.forEach { v ->
                v.nextFocusLeftId = targets.getOrNull(c - 1)?.id ?: v.id
                v.nextFocusRightId = targets.getOrNull(c + 1)?.id ?: v.id
            }
        }
        val hasColumns = colViews.any { it.isNotEmpty() }
        if (hasColumns) sidePanel.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
        if (note != null) sidePanel.addView(PlayerUi.text(this, 16f, SECONDARY).apply {
            text = note
            setPadding(dp(14), dp(18), 0, 0)
        })
        if (!hasColumns && note == null && emptyText != null) sidePanel.addView(PlayerUi.text(this, 15f, SECONDARY).apply {
            text = emptyText
            maxWidth = colWidth * 2
            setPadding(0, dp(18), 0, 0)
        })
        // Section « Image et son » : OK bascule Activé / Désactivé (le panneau reste ouvert).
        val toggleViews = ArrayList<View>()
        if (toggles.isNotEmpty()) {
            sidePanel.addView(PlayerUi.text(this, 14f, SECONDARY, PlayerUi.Weight.MEDIUM).apply {
                text = "Image et son"; setPadding(dp(14), dp(26), 0, dp(8))
            })
            for (t in toggles) {
                val value = PlayerUi.text(this, 15f, SECONDARY).apply {
                    setTextColor(PlayerUi.focusTextColors(SECONDARY))
                    isDuplicateParentStateEnabled = true
                }
                fun refresh() { value.text = if (t.isOn()) "Activé" else "Désactivé" }
                refresh()
                val v = LinearLayout(this).apply {
                    id = View.generateViewId()
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumWidth = if (hasColumns) colWidth else dp(if (touch) 300 else 420)
                    setPadding(dp(14), dp(12), dp(16), dp(12))
                    background = PlayerUi.focusBackground(this@PlayerActivity, 10f)
                    isFocusable = true
                    isFocusableInTouchMode = false
                    isClickable = true
                    addView(PlayerUi.text(this@PlayerActivity, 17f, Color.WHITE).apply {
                        text = t.label
                        setTextColor(PlayerUi.focusTextColors())
                        isDuplicateParentStateEnabled = true
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(value, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(24) })
                    setOnClickListener { t.onToggle(!t.isOn()); refresh() }
                }
                toggleViews += v
                sidePanel.addView(v, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            }
            toggleViews.forEachIndexed { i, v ->
                v.nextFocusUpId = toggleViews.getOrNull(i - 1)?.id ?: targets.firstOrNull()?.id ?: v.id
                v.nextFocusDownId = toggleViews.getOrNull(i + 1)?.id ?: v.id
                v.nextFocusLeftId = v.id; v.nextFocusRightId = v.id
            }
            colViews.forEach { views -> views.lastOrNull()?.nextFocusDownId = toggleViews.first().id }
        }
        sidePanel.addView(PlayerUi.text(this, 13f, SECONDARY).apply {
            text = if (touch) "Touche une option · touche l'image pour fermer" else "OK pour choisir · Retour pour fermer"
            setPadding(dp(14), dp(20), 0, 0)
        })
        panelKind = kind
        sidePanel.animate().cancel()
        sidePanel.alpha = 0f
        sidePanel.translationX = dp(40).toFloat()
        sidePanel.visibility = View.VISIBLE
        sidePanel.animate().alpha(1f).translationX(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        hideNextCard()
        hidePillNow()
        if (!touch) (targets.firstOrNull() ?: toggleViews.firstOrNull())?.let { v -> v.post { v.requestFocus() } }
    }

    /** Ligne d'option : coche (choix en cours) + libellé ; fond blanc et texte noir au focus. */
    private fun optionRow(label: String, selected: Boolean, maxWidthPx: Int, onClick: () -> Unit): View {
        val colors = PlayerUi.focusTextColors()
        return LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = PlayerUi.focusBackground(this@PlayerActivity, 10f)
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            setOnClickListener { onClick() }
            addView(ImageView(this@PlayerActivity).apply {
                setImageDrawable(IconDrawable(PlayerIcon.CHECK, dp(20), colors, PlayerUi.font(this@PlayerActivity, PlayerUi.Weight.SEMIBOLD)))
                isDuplicateParentStateEnabled = true
                visibility = if (selected) View.VISIBLE else View.INVISIBLE
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(PlayerUi.text(this@PlayerActivity, 17f, Color.WHITE, if (selected) PlayerUi.Weight.SEMIBOLD else PlayerUi.Weight.REGULAR).apply {
                text = label
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                maxWidth = maxWidthPx
                setTextColor(if (selected) colors else PlayerUi.focusTextColors(0xFFD5D9E2.toInt()))
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        }
    }

    private fun setAfrEnabled(on: Boolean) {
        appPrefs.edit().putBoolean(KEY_AFR, on).apply()
        if (on) {
            player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            afr.onVideoFormat(player.videoFormat)
        } else {
            afr.restore()
            player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        }
        showPill("Fréquence d'image auto : ${if (on) "activée" else "désactivée"}", 2_500)
    }

    private fun setNightEnabled(on: Boolean) {
        appPrefs.edit().putBoolean(KEY_NIGHT, on).apply()
        night.enabled = on
        night.update(player.audioSessionId, player.audioFormat?.channelCount ?: 2)
        val mime = player.audioFormat?.sampleMimeType
        if (on && mime != null && mime in BITSTREAM_AUDIO) showPill("Mode nuit indisponible en sortie audio directe", 4_000)
        else showPill("Mode nuit : ${if (on) "activé" else "désactivé"}", 2_500)
    }

    /** Pastille d'aide « ▼ Audio et sous-titres » : au premier démarrage VOD, 3 fois au plus. */
    private fun maybeShowTrackHint() {
        val item = current ?: return
        // Tactile : pas d'aide aux touches de télécommande (boutons visibles dans la barre du haut).
        if (touch) return
        if (item.live) { maybeShowListHint(); return }
        if (trackHintDone) return
        if (item.live || panelKind != PanelKind.NONE || pill.visibility == View.VISIBLE) return
        if (!player.isPlaying) return
        trackHintDone = true
        val count = trackPrefs.getInt(KEY_HINT_COUNT, 0)
        if (count >= 3) return
        trackPrefs.edit().putInt(KEY_HINT_COUNT, count + 1).apply()
        showPill("MENU : audio et sous-titres", 5_000)
    }

    /** Pastille d'aide « ▲▼ Liste des chaînes » : au premier démarrage d'une chaîne, 3 fois au plus. */
    private fun maybeShowListHint() {
        if (listHintDone || PlayerSession.queue.size < 2) return
        if (panelKind != PanelKind.NONE || chListOpen || pill.visibility == View.VISIBLE) return
        if (!player.isPlaying) return
        listHintDone = true
        val count = trackPrefs.getInt(KEY_LIST_HINT_COUNT, 0)
        if (count >= 3) return
        trackPrefs.edit().putInt(KEY_LIST_HINT_COUNT, count + 1).apply()
        showPill("▲ ▼ : liste des chaînes", 5_000)
    }

    /** Clé d'une reprise à recaler sur la vraie durée dès que le lecteur la connaît (reprise estimée). */
    private var exactResumeKey: String? = null
    /** Change à chaque élément lancé et à « Depuis le début » : un recalage différé périmé est ignoré. */
    private var exactResumeGen = 0

    private fun savedProgress(item: PlayItem): Progress? {
        val key = item.progressKey ?: return null
        if (item.live) return null
        // Fichiers (local, réseau) : reprise propre à l'appareil, hors « Continuer à regarder ».
        if (item.type == com.fluxfr.tv.data.files.MediaFiles.TYPE) {
            return runCatching { com.fluxfr.tv.data.files.FileResume.get(this, key) }.getOrNull()
        }
        return runCatching { store.get(key) }.getOrNull()
    }

    private fun resumePosition(p: Progress?): Long {
        if (p == null) return 0
        return if (p.position > 10_000 && !p.finished) p.position else 0
    }

    /**
     * Lance la source courante. VOD : [positionMs] = position de reprise. Direct : heure (ms depuis 1970) où
     * reprendre dans le tampon du contrôle du direct, 0 = au direct.
     */
    private fun startSource(item: PlayItem, positionMs: Long, autoPlay: Boolean = true) {
        val source = item.sources.getOrNull(sourceIndex) ?: return
        handler.removeCallbacks(retryRunnable)
        val builder = MediaItem.Builder()
            .setUri(source.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
        if (source.url.contains(".m3u8", ignoreCase = true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        // Fichier (onglet « Fichiers ») : sous-titres .srt du même nom, proposés dans le choix des sous-titres.
        item.subtitleUri?.let { sub ->
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLabel("Fichier .srt")
                        .build()
                )
            )
        }
        loadControl.live = item.live
        // Direct : on garde la dernière image (assombrie) pendant que la chaîne suivante démarre.
        playerView.setKeepContentOnPlayerReset(item.live)
        if (item.live) {
            zapStartAt = SystemClock.elapsedRealtime()
            zapFirstFrameLogged = false
        } else zapStartAt = 0
        // Direct MPEG-TS : lecture depuis le tampon local enregistré (une seule connexion au fournisseur).
        if (item.live && startTimeshift(item, source.url)) {
            val s = ts!!
            val offset = when {
                positionMs > 0 && s.hasData -> s.buffer.seekOffset(positionMs)
                s.hasData -> s.buffer.liveOffset()
                else -> s.buffer.start
            }
            playFromBuffer(offset, autoPlay)
            return
        }
        tsActive = false
        if (item.live || positionMs <= 0) player.setMediaItem(builder.build())
        else player.setMediaItem(builder.build(), positionMs)
        player.prepare()
        player.playWhenReady = autoPlay
    }

    // ---------------------------------------------------------------------------------------------
    // Contrôle du direct : pause, retour en arrière, retour au direct
    // ---------------------------------------------------------------------------------------------

    /**
     * Prépare l'enregistrement local de [url] pour [item] (réutilisé s'il existe déjà). false : lecture
     * directe classique (HLS, source en échec, espace disque insuffisant…).
     */
    private fun startTimeshift(item: PlayItem, url: String): Boolean {
        if (!Timeshift.isEligible(url) || url in tsDisabledUrls) {
            releaseTimeshift()
            return false
        }
        // Le lecteur lit encore un flux du fournisseur (replay, chaîne HLS) : il le lâche avant que
        // l'enregistreur se connecte (comptes à une connexion, cf. ProviderConnections).
        releaseProviderStream()
        val cur = ts
        if (cur != null && !cur.released && cur.url == url && cur.itemId == item.itemId && !cur.buffer.failed) {
            cur.resumeRecording()
            tsActive = true
            return true
        }
        releaseTimeshift()
        val s = Timeshift.start(this, item.itemId, url, Settings(this).userAgent)
        if (s == null) {
            if (!tsSpaceWarned) {
                tsSpaceWarned = true
                showPill("Contrôle du direct indisponible (espace insuffisant)", 5_000)
            }
            return false
        }
        ts = s
        tsActive = true
        return true
    }

    /** Arrête la lecture d'un flux HTTP du fournisseur (pas du tampon local) : la connexion se ferme. */
    private fun releaseProviderStream() {
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return
        if (uri.scheme == Timeshift.SCHEME) return
        if (ProviderConnections.singleConnection) player.stop()
    }

    /** Arrête l'enregistrement du direct et efface son tampon. */
    private fun releaseTimeshift() {
        cancelLiveSeek()
        handler.removeCallbacks(stopRecordingRunnable)
        ts?.release()
        ts = null
        tsActive = false
        tsBaseWall = -1L
    }

    /** (Re)lance la lecture du tampon à la position [offset] (octets). */
    private fun playFromBuffer(offset: Long, autoPlay: Boolean = true) {
        val s = ts ?: return
        val item = current ?: return
        tsActive = true
        tsBaseOffset = offset
        tsBaseWall = -1L
        val media = MediaItem.Builder()
            .setUri(Timeshift.uri(s.id, offset))
            .setMimeType(MimeTypes.VIDEO_MP2T)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
            .build()
        player.setMediaItem(media)
        player.prepare()
        player.playWhenReady = autoPlay
    }

    /** Heure (ms) de l'image lue dans le tampon ; -1 si inconnue. */
    private fun tsPlayWall(): Long {
        val s = ts ?: return -1L
        if (!tsActive) return -1L
        if (tsBaseWall < 0) {
            if (!s.hasData) return -1L
            tsBaseWall = s.buffer.wallAt(tsBaseOffset)
            if (tsBaseWall <= 0) { tsBaseWall = -1L; return -1L }
        }
        return tsBaseWall + player.currentPosition.coerceAtLeast(0)
    }

    /** Flux HLS dont la liste de lecture offre une fenêtre de direct (DVR) d'au moins une minute. */
    private fun hlsDvr(): Boolean {
        if (tsActive || current?.live != true) return false
        if (!player.isCurrentMediaItemDynamic || !player.isCurrentMediaItemSeekable) return false
        val d = player.duration
        return d != C.TIME_UNSET && d >= 60_000
    }

    /** Position « direct » de la fenêtre HLS (position par défaut du lecteur). */
    private fun hlsLivePosition(): Long {
        val tl = player.currentTimeline
        if (tl.isEmpty) return player.duration.coerceAtLeast(0)
        val w = tl.getWindow(player.currentMediaItemIndex, Timeline.Window())
        return w.defaultPositionMs.takeIf { it > 0 } ?: player.duration.coerceAtLeast(0)
    }

    /** Le contrôle du direct est possible pour la lecture en cours. */
    private fun liveControl(): Boolean {
        val item = current ?: return false
        if (!item.live) return false
        if (tsActive) return ts?.hasData == true && tsPlayWall() > 0
        return hlsDvr()
    }

    /** État affiché par la barre ; [pendingBehind] : retard visé pendant un saut. */
    private fun liveState(pendingBehind: Long? = liveSeekBehind): TimeshiftState? {
        val item = current ?: return null
        if (!item.live) return null
        val first: Long
        val live: Long
        val play: Long
        if (tsActive) {
            val s = ts ?: return null
            first = s.firstWall
            live = s.lastWall
            if (first <= 0 || live <= 0) return null
            play = (tsPlayWall().takeIf { it > 0 } ?: return null).coerceIn(first, live)
        } else if (hlsDvr()) {
            val now = System.currentTimeMillis()
            val livePos = hlsLivePosition()
            first = now - livePos
            live = now
            play = (now - (livePos - player.currentPosition).coerceAtLeast(0)).coerceIn(first, live)
        } else return null
        val shown = pendingBehind?.let { (live - it).coerceIn(first, live) } ?: play
        val prog = Epg.cachedAt(item.itemId, shown) ?: Epg.cachedAt(item.itemId, live)
        return TimeshiftState(
            windowStartWall = first,
            liveWall = live,
            playWall = shown,
            paused = !player.playWhenReady,
            programTitle = prog?.title,
            programStart = prog?.startMs ?: 0L,
            programEnd = prog?.endMs ?: 0L,
            pendingDeltaMs = if (pendingBehind != null) liveSeekDelta else null,
        )
    }

    /** La pastille de messages remonte au-dessus des commandes quand celles-ci sont affichées. */
    private fun updatePillOffset() {
        if (!::pill.isInitialized || !::overlay.isInitialized) return
        val lp = pill.layoutParams as? FrameLayout.LayoutParams ?: return
        val shown = overlay.visible && overlay.mode != ControlOverlay.Mode.HEADER
        val want = if (shown) overlay.bottomHeight() + dp(16) else dp(48)
        if (lp.bottomMargin != want) { lp.bottomMargin = want; pill.layoutParams = lp }
    }

    private fun cancelLiveSeek() {
        handler.removeCallbacks(commitLiveSeek)
        liveSeekBehind = null
        liveSeekDelta = 0
    }

    /** Saut de ±[baseStep] dans le direct (accéléré si la touche reste enfoncée), appliqué 0,7 s après la dernière touche. */
    private fun liveSeekStep(forward: Boolean, repeat: Int, baseStep: Long) {
        val st = liveState(null) ?: return
        val now = SystemClock.elapsedRealtime()
        if (repeat > 0 && now - lastLiveSeekAt < 150) return
        lastLiveSeekAt = now
        if (forward && liveSeekBehind == null && st.atLive) {
            refreshOverlay()
            showPill("Vous êtes déjà en direct", 1_500)
            return
        }
        val step = when {
            repeat < 10 -> baseStep
            repeat < 30 -> baseStep * 3
            else -> baseStep * 6
        }
        val window = st.liveWall - st.windowStartWall
        val base = liveSeekBehind ?: st.behindMs.also { liveSeekDelta = 0 }
        val target = (base + if (forward) -step else step).coerceIn(0, (window - 1_000).coerceAtLeast(0))
        liveSeekDelta += base - target
        liveSeekBehind = target
        handler.removeCallbacks(hideOverlayRunnable)
        refreshOverlay()
        handler.removeCallbacks(commitLiveSeek)
        handler.postDelayed(commitLiveSeek, SEEK_COMMIT_MS)
    }

    private fun applyLiveSeek() {
        handler.removeCallbacks(commitLiveSeek)
        val behind = liveSeekBehind ?: return
        val delta = liveSeekDelta
        liveSeekBehind = null
        liveSeekDelta = 0
        if (delta != 0L) seekBehindLive(behind)
        if (overlay.visible) { refreshOverlay(); scheduleOverlayHide() }
    }

    /** Lecture à [behind] ms du direct (moins de 5 s : retour au direct). */
    private fun seekBehindLive(behind: Long) {
        if (behind < TimeshiftState.LIVE_EDGE_MS) { goLive(); return }
        if (tsActive) {
            val s = ts ?: return
            val last = s.lastWall
            if (last <= 0) return
            playFromBuffer(s.buffer.seekOffset(last - behind), autoPlay = player.playWhenReady)
        } else if (hlsDvr()) {
            player.seekTo((hlsLivePosition() - behind).coerceAtLeast(0))
        }
    }

    /** Retour au direct (ou, pendant un replay lancé depuis le direct, retour à la chaîne). */
    private fun goLive() {
        cancelLiveSeek()
        if (catchupOverride != null) { backToLive(); return }
        val st = liveState(null)
        if (st != null && st.atLive) {
            if (!player.playWhenReady) player.play()
            showOverlay()
            return
        }
        if (tsActive) {
            val s = ts ?: return
            playFromBuffer(s.buffer.liveOffset(), autoPlay = true)
        } else if (hlsDvr()) {
            player.seekToDefaultPosition()
            player.play()
        } else return
        showOverlay()
        // Le bouton « Direct » disparaît : le focus revient sur lecture / pause.
        overlay.focusPlayPause()
    }

    private fun liveTogglePause(play: Boolean = !player.playWhenReady) {
        if (play) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        } else player.pause()
        if (overlay.visible) { refreshOverlay(); scheduleOverlayHide() }
    }

    /**
     * Programme en cours (ou regardé en différé) qui peut être repris au début : depuis le tampon local
     * s'il le couvre, sinon par le replay du fournisseur. null si aucun.
     */
    private fun restartableProgram(item: PlayItem): EpgProgram? {
        if (!item.live) return null
        val now = System.currentTimeMillis()
        val st = liveState(null)
        val at = st?.playWall ?: now
        val prog = Epg.cachedAt(item.itemId, at) ?: Epg.cached(item.itemId).firstOrNull { it.isNow(now) } ?: return null
        if (at - prog.startMs < Catchup.MIN_ELAPSED_MS) return null
        if (st != null && st.windowStartWall <= prog.startMs) return prog
        if (Catchup.replayable(item.archiveDays, prog, now) && Catchup.url(this, item.itemId, prog.startMs, prog.endMs) != null) return prog
        return null
    }

    /** « Reprendre au début du programme ». */
    private fun restartProgram() {
        val item = current ?: return
        val prog = restartableProgram(item) ?: run { showPill("Début du programme indisponible", 2_500); return }
        val st = liveState(null)
        if (st != null && st.windowStartWall <= prog.startMs) {
            cancelLiveSeek()
            seekBehindLive((st.liveWall - prog.startMs).coerceAtLeast(TimeshiftState.LIVE_EDGE_MS))
            showOverlay()
            return
        }
        startCatchup(item, prog)
    }

    /** Replay du fournisseur depuis le début de [prog] (l'enregistrement local est arrêté : une seule connexion). */
    private fun startCatchup(live: PlayItem, prog: EpgProgram) {
        val c = Catchup.item(this, live, prog) ?: run { showPill("Replay indisponible", 2_500); return }
        handler.removeCallbacks(commitZap)
        pendingZapIndex = null
        releaseTimeshift()
        catchupOverride = c
        playCurrent(keepCatchup = true)
        showPill("Replay depuis ${hourFormat.format(Date(prog.startMs))} · Retour : revenir au direct", 5_000)
    }

    /** Fin du replay lancé depuis le direct : retour à la chaîne. */
    private fun backToLive() {
        if (catchupOverride == null) return
        catchupOverride = null
        playCurrent()
    }


    // ---------------------------------------------------------------------------------------------
    // Erreurs et sources de secours
    // ---------------------------------------------------------------------------------------------

    private fun handleError(error: PlaybackException) {
        val item = current ?: return
        lastError = error
        if (item.live && tsActive) {
            val s = ts
            if (s == null || s.released || s.buffer.failed) {
                // Enregistrement impossible (flux non MPEG-TS, refus du serveur, disque plein…) : lecture directe,
                // sans contrôle du direct.
                val diskFull = s?.buffer?.writeFailed == true
                s?.let { tsDisabledUrls += it.url }
                releaseTimeshift()
                startSource(item, 0, autoPlay = retryAutoPlay())
                if (diskFull) showPill("Contrôle du direct indisponible (espace insuffisant)", 5_000)
                return
            }
            tsRetryWall = tsPlayWall().coerceAtLeast(0)
        }
        val decoderError = error.errorCode in 4000..4999
        if (!decoderError && sameSourceRetries < 2) {
            val delay = if (sameSourceRetries == 0) 1_000L else 3_000L
            sameSourceRetries++
            retryPositionMs = if (item.live) 0 else player.currentPosition
            showPill("Nouvelle tentative…", 2_500)
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, delay)
            return
        }
        nextSourceOrFail(error)
    }

    /** Bascule automatique sur la version suivante (souvent moins bonne qualité), sinon panneau d'erreur. */
    private fun nextSourceOrFail(error: PlaybackException?) {
        val item = current ?: return
        autoFailures++
        if (autoFailures < item.sources.size) {
            sourceIndex = (sourceIndex + 1) % item.sources.size
            sameSourceRetries = 0
            val pos = if (item.live) 0 else player.currentPosition
            startSource(item, pos, autoPlay = retryAutoPlay())
            showPill("Problème de lecture, bascule sur la source ${sourceIndex + 1}…", 3_000)
        } else {
            showErrorPanel(error)
        }
    }

    private fun errorMessage(error: PlaybackException?): String {
        if (error == null) return "Lecture impossible"
        val http = generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        // Onglet « Fichiers » : message du partage réseau (identifiants, hôte…), ou fichier local.
        generateSequence(error.cause) { it.cause }.filterIsInstance<com.fluxfr.tv.data.files.FilesIOException>()
            .firstOrNull()?.message?.let { return it }
        if (current?.type == com.fluxfr.tv.data.files.MediaFiles.TYPE) {
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> return "Fichier introuvable (support retiré ?)"
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> return "Accès au fichier refusé"
            }
        }
        return when {
            http != null -> "Le flux est indisponible (erreur ${http.responseCode})"
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Le flux est indisponible"
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> "Le serveur ne répond pas"
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Le flux est indisponible (erreur 404)"
            error.errorCode in 4000..4999 -> "Format vidéo non supporté par cet appareil"
            else -> "Lecture impossible"
        }
    }

    private fun showErrorPanel(error: PlaybackException?, title: String? = null) {
        val item = current ?: return
        hideZapOverlay()
        zapStartAt = 0
        // Plus de lecture : l'enregistrement du direct libère la connexion (repris par « Réessayer »).
        ts?.stopRecording()
        // VOD : « Réessayer » reprend à la dernière position lue.
        val lastPosition = if (item.live) 0L else player.currentPosition
        val buttons = ArrayList<Pair<String, () -> Unit>>()
        buttons += "Réessayer" to {
            hidePanel()
            autoFailures = 0
            sameSourceRetries = 0
            startSource(item, lastPosition)
        }
        if (item.sources.size > 1) buttons += "Source suivante" to {
            hidePanel()
            switchSource(1)
        }
        buttons += "Retour" to { finish() }
        showPanel(
            PanelKind.ERROR,
            title ?: errorMessage(error),
            error?.errorCodeName?.let { "${item.title} · $it" } ?: item.title,
            buttons,
            0,
        )
    }

    /** Changer manuellement de version de la chaîne (MENU › Qualité ; ◀ / ▶ sans contrôle du direct). */
    private fun switchSource(delta: Int) {
        val item = current ?: return
        if (item.sources.size < 2) { showPill("Une seule source pour cette chaîne"); return }
        sourceIndex = (sourceIndex + delta + item.sources.size) % item.sources.size
        autoFailures = 0
        sameSourceRetries = 0
        startSource(item, if (item.live) 0 else player.currentPosition)
        overlayOnReady = true
        showOverlay()
    }

    private fun onEnded() {
        val item = current ?: return
        if (item.live) {
            // Flux coupé : on relance (3 fois par minute au plus), puis source suivante.
            val now = SystemClock.elapsedRealtime()
            liveRestarts.removeAll { now - it > 60_000 }
            if (liveRestarts.size < 3) {
                liveRestarts += now
                val s = ts
                if (tsActive && s != null && !s.released) playFromBuffer(s.buffer.liveOffset())
                else if (tsActive) startSource(item, 0)
                else {
                    player.seekToDefaultPosition()
                    player.prepare()
                }
            } else {
                liveRestarts.clear()
                nextSourceOrFail(null)
            }
            return
        }
        // Fin prématurée (flux coupé par le serveur, durée inconnue) : ce n'est pas la fin du film / de l'épisode.
        val d = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        if (!PlaybackRules.endedNaturally(player.currentPosition, d)) {
            Log.w("FluxPlayer", "Fin de flux prématurée (${player.currentPosition} / $d ms)")
            saveProgress()
            showErrorPanel(null, title = "La lecture s'est interrompue")
            return
        }
        saveProgress(finished = true)
        if (PlayerSession.index + 1 < PlayerSession.queue.size) {
            PlayerSession.index++
            playCurrent() // épisode suivant
        } else finish()
    }

    /**
     * Enregistre la progression de l'élément VOD en cours. Rien tant que sa durée réelle n'est pas connue
     * (avant STATE_READY, la ligne perdrait sa durée et son indicateur « estimée »), ni si la position n'a pas
     * changé. [sync] : écriture immédiate (onStop) ; sinon sur l'exécuteur d'arrière-plan.
     */
    private fun saveProgress(finished: Boolean = false, sync: Boolean = false) {
        val item = current ?: return
        val key = item.progressKey ?: return
        if (item.live) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val known = duration > 0 && durationKnownFor == key
        val position = if (finished && known) duration else player.currentPosition
        val last = if (lastSavedKey == key) lastSavedPosition else null
        if (!PlaybackRules.shouldSave(known, position, last)) {
            // onStop : la dernière écriture différée est peut-être encore en file (l'exécuteur peut être arrêté
            // avant de la traiter) ; elle est alors faite ici.
            if (!(sync && known && position >= 5_000 && savedSeq.get() != saveSeq.get())) return
        }
        lastSavedKey = key
        lastSavedPosition = position
        val p = Progress(
            key = key, type = item.type, itemId = item.itemId, title = item.title, subtitle = item.subtitle,
            poster = item.poster, url = item.sources.firstOrNull()?.url, position = position,
            duration = duration, updated = System.currentTimeMillis(),
            seriesId = item.seriesId, season = item.season, episode = item.episode,
        )
        val seq = saveSeq.incrementAndGet()
        // Fichiers (local, réseau) : reprise propre à l'appareil (FileResume), jamais dans ProgressStore.
        val ctx = applicationContext
        val write: (Progress) -> Unit =
            if (item.type == com.fluxfr.tv.data.files.MediaFiles.TYPE) { pr -> com.fluxfr.tv.data.files.FileResume.save(ctx, pr) }
            else { pr -> store.save(pr) }
        if (sync) { runCatching { write(p) }; savedSeq.set(seq); return }
        val queued = runCatching {
            infoExecutor.execute {
                // Une sauvegarde plus récente (onStop, synchrone) est déjà passée : celle-ci est périmée.
                if (saveSeq.get() == seq) { runCatching { write(p) }; savedSeq.set(seq) }
            }
        }
        if (queued.isFailure) { runCatching { write(p) }; savedSeq.set(seq) }
    }

    // ---------------------------------------------------------------------------------------------
    // Zapping (direct)
    // ---------------------------------------------------------------------------------------------

    private fun numberOf(i: Int): Int = PlayerSession.queue.getOrNull(i)?.number ?: (i + 1)

    /** Prévisualise immédiatement la chaîne cible, lance la lecture 450 ms après la dernière touche. */
    private fun zap(delta: Int, repeat: Int) {
        val size = PlayerSession.queue.size
        if (size < 2) return
        val now = SystemClock.elapsedRealtime()
        if (repeat > 0 && now - lastZapStepAt < 250) return // touche maintenue : on limite la cadence
        lastZapStepAt = now
        val from = pendingZapIndex ?: PlayerSession.index
        val target = (from + delta + size) % size
        pendingZapIndex = target
        showZapPreview(target)
        handler.removeCallbacks(commitZap)
        handler.postDelayed(commitZap, 450)
    }

    private fun goToChannel(target: Int) {
        if (target !in PlayerSession.queue.indices) return
        if (target == PlayerSession.index && catchupOverride == null) { showOverlay(); return }
        if (target == PlayerSession.index) { backToLive(); return }
        previousChannelIndex = PlayerSession.index
        PlayerSession.index = target
        PlayerSession.queue.getOrNull(target)?.takeIf { it.live }?.let { showZapOverlay(it, target) }
        playCurrent()
    }

    // ---------------------------------------------------------------------------------------------
    // Zapping : image précédente assombrie + logo et nom de la chaîne pendant le démarrage
    // ---------------------------------------------------------------------------------------------

    private fun buildZapOverlay(root: FrameLayout) {
        zapLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        zapName = PlayerUi.text(this, 24f, Color.WHITE, PlayerUi.Weight.SEMIBOLD).apply {
            gravity = Gravity.CENTER
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(24), dp(16), dp(24), 0)
            setShadowLayer(10f, 0f, 2f, 0xCC000000.toInt())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(zapLogo, LinearLayout.LayoutParams(dp(200), dp(120)))
            addView(zapName, LinearLayout.LayoutParams(-2, -2))
        }
        zapOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            visibility = View.GONE
            addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        root.addView(zapOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showZapOverlay(item: PlayItem, index: Int) {
        val logo = item.poster?.takeIf { it.isNotBlank() }
        if (logo != null) {
            zapLogo.visibility = View.VISIBLE
            zapLogo.load(logo) { crossfade(false) }
        } else {
            zapLogo.dispose()
            zapLogo.setImageDrawable(null)
            zapLogo.visibility = View.GONE
        }
        zapName.text = "${item.number ?: (index + 1)} · ${item.title}"
        zapOverlay.animate().cancel()
        zapOverlay.alpha = 1f
        zapOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideZapOverlayRunnable)
        handler.postDelayed(hideZapOverlayRunnable, 15_000) // sécurité
    }

    private fun hideZapOverlay() {
        if (!::zapOverlay.isInitialized || zapOverlay.visibility != View.VISIBLE) return
        handler.removeCallbacks(hideZapOverlayRunnable)
        zapOverlay.animate().cancel()
        zapOverlay.animate().alpha(0f).setDuration(180)
            .withEndAction { zapOverlay.visibility = View.GONE }.start()
    }

    private fun typeDigit(d: Int) {
        if (digitBuffer.length >= 4) digitBuffer = ""
        digitBuffer += d
        digitsView.text = digitBuffer + "_"
        digitsView.visibility = View.VISIBLE
        handler.removeCallbacks(commitDigits)
        handler.postDelayed(commitDigits, 1_500)
    }

    private fun applyDigits() {
        val n = digitBuffer.toIntOrNull()
        digitBuffer = ""
        digitsView.visibility = View.GONE
        if (n == null) return
        val idx = PlayerSession.queue.indices.firstOrNull { numberOf(it) == n }
        if (idx == null) { showPill("Chaîne $n introuvable"); return }
        handler.removeCallbacks(commitZap)
        pendingZapIndex = null
        goToChannel(idx)
    }

    private fun toggleLastChannel() {
        val prev = previousChannelIndex ?: run { showPill("Pas de chaîne précédente"); return }
        handler.removeCallbacks(commitZap)
        pendingZapIndex = null
        goToChannel(prev)
    }

    // ---------------------------------------------------------------------------------------------
    // Avance / retour rapide et pause (VOD)
    // ---------------------------------------------------------------------------------------------

    /**
     * Déplacement de ±[baseStep] (accéléré si la touche reste enfoncée) : la cible s'affiche sur la barre
     * (bulle + aperçu) et la lecture y saute après une courte pause, ou tout de suite avec OK.
     */
    private fun seekStep(forward: Boolean, repeat: Int, baseStep: Long = 10_000L) {
        val now = SystemClock.elapsedRealtime()
        if (repeat > 0 && now - lastSeekStepAt < 150) return
        lastSeekStepAt = now
        val step = when {
            repeat < 10 -> baseStep
            repeat < 30 -> baseStep * 3
            else -> baseStep * 6
        }
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val base = seekTarget ?: player.currentPosition.also { seekDelta = 0 }
        var target = base + if (forward) step else -step
        target = target.coerceAtLeast(0)
        if (duration != null) target = target.coerceAtMost((duration - 1_000).coerceAtLeast(0))
        seekDelta += target - base
        seekTarget = target
        handler.removeCallbacks(hideOverlayRunnable)
        if (overlay.visible) refreshOverlay()
        handler.removeCallbacks(commitSeek)
        handler.postDelayed(commitSeek, SEEK_COMMIT_MS)
    }

    private fun applySeek() {
        handler.removeCallbacks(commitSeek)
        val target = seekTarget ?: return
        seekTarget = null
        seekDelta = 0
        player.seekTo(target)
        if (overlay.visible) { refreshOverlay(); scheduleOverlayHide() }
        updateNextCard(target)
    }

    /** Applique tout de suite le déplacement en préparation (VOD ou direct) ; false s'il n'y en a pas. */
    private fun commitPendingSeek(): Boolean {
        if (seekTarget != null) { applySeek(); return true }
        if (liveSeekBehind != null) { applyLiveSeek(); return true }
        return false
    }

    private fun togglePlayPause(play: Boolean = !player.playWhenReady) {
        if (play) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        } else {
            player.pause()
        }
        if (overlay.visible) { refreshOverlay(); scheduleOverlayHide() }
    }

    // ---------------------------------------------------------------------------------------------
    // Écran de pause (VOD)
    // ---------------------------------------------------------------------------------------------

    /** (Re)programme l'écran de pause 5 s plus tard si la lecture VOD est en pause. */
    private fun armPauseScreen() {
        handler.removeCallbacks(showPauseRunnable)
        val item = current ?: return
        if (item.live || player.playWhenReady || player.playbackState == Player.STATE_ENDED) return
        handler.postDelayed(showPauseRunnable, PAUSE_SCREEN_DELAY_MS)
    }

    private fun maybeShowPauseScreen() {
        val item = current ?: return
        if (item.live || player.playWhenReady || player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
        ) return
        if (panelKind != PanelKind.NONE || chListOpen || seekTarget != null) return
        hidePillNow()
        pauseScreen.show(pauseInfo(item))
        // Même barre de progression que les commandes, seule, par-dessus l'écran de pause.
        refreshOverlay()
        overlay.show(ControlOverlay.Mode.PAUSE)
        handler.removeCallbacks(hideOverlayRunnable)
        handler.removeCallbacks(overlayTicker)
        handler.postDelayed(overlayTicker, 500)
        updatePillOffset()
    }

    /** Quitte l'écran de pause : commandes complètes (toujours en pause), focus sur ⏯. */
    private fun exitPauseScreen() {
        pauseScreen.hide()
        if (panelKind == PanelKind.NONE && !chListOpen) showOverlay(OverlayFocus.PLAY) else hideOverlay()
        armPauseScreen()
    }

    private fun pauseInfo(item: PlayItem): PauseInfo {
        val extra = vodExtras[item.itemId]
        val episodeLine = episodeLine(item)
        val durationMs = extra?.durationSecs?.takeIf { it > 0 }?.times(1000)
            ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val meta = listOfNotNull(
            item.year ?: extra?.year,
            durationMs?.let { PlayerTime.runtime(it) },
        ).joinToString("  ·  ").ifEmpty { null }
        return PauseInfo(
            title = item.title,
            episodeLine = episodeLine,
            meta = meta,
            plot = item.plot ?: extra?.plot,
            cast = item.cast ?: extra?.cast,
        )
    }

    /** « Série - S01E03 - Titre » → « Titre » ; null si le titre n'apporte rien. */
    private fun cleanEpisodeTitle(raw: String?, seriesTitle: String): String? {
        var t = raw?.trim() ?: return null
        EPISODE_CODE.find(t)?.let { m -> t = t.substring(m.range.last + 1) }
        t = t.trim().trimStart('-', '–', ':', '·').trim()
        if (t.isEmpty() || t.equals(seriesTitle, ignoreCase = true)) return null
        if (EPISODE_ONLY.matches(t)) return null
        return t
    }

    /** Fiche du film / de l'épisode (résumé, casting…) récupérée en arrière-plan via l'API Xtream. */
    private fun requestVodExtra(item: PlayItem) {
        if (item.live || item.itemId in vodExtraRequested) return
        val movie = item.type == Progress.MOVIE
        val episode = item.type == Progress.EPISODE && !item.seriesId.isNullOrBlank()
        if (!movie && !episode) return
        if (item.plot != null && (item.cast != null || episode)) return
        val s = Settings(this)
        if (s.mode != Settings.MODE_XTREAM || s.server.isBlank()) return
        val idOk = if (movie) item.itemId.all { it.isDigit() } else item.seriesId!!.all { it.isDigit() }
        if (!idOk || item.itemId.isEmpty()) return
        vodExtraRequested += item.itemId
        runCatching {
            infoExecutor.execute {
                val extra = runCatching {
                    val client = XtreamClient(s.server, s.user, s.pass)
                    if (movie) {
                        val m = client.vodInfo(item.itemId)
                        VodExtra(m.plot, m.year, m.cast, m.durationSecs, null)
                    } else {
                        val info = client.seriesInfo(item.seriesId!!)
                        val ep = info.episodes.firstOrNull { it.id == item.itemId }
                        VodExtra(
                            ep?.plot?.takeIf { it.isNotBlank() } ?: info.plot?.takeIf { it.isNotBlank() },
                            null, null, ep?.durationSec ?: 0L, ep?.title,
                        )
                    }
                }.onFailure { Log.w("FluxPause", "Fiche indisponible pour ${item.itemId}", it) }.getOrNull()
                handler.post {
                    if (isDestroyed) return@post
                    if (extra == null) { vodExtraRequested.remove(item.itemId); return@post }
                    vodExtras[item.itemId] = extra
                    val cur = current
                    if (cur != null && cur.itemId == item.itemId && pauseScreen.isShown) pauseScreen.bind(pauseInfo(cur))
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Touches de la télécommande
    // ---------------------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && code == KeyEvent.KEYCODE_DPAD_RIGHT) goLiveHeld = false
        if (event.action == KeyEvent.ACTION_UP && consumedKeys.remove(code)) {
            // Les déplacements (◀ ▶, ⏪ ⏩) sont appliqués après une courte pause ou avec OK, pas au relâchement.
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) {
            // Panneau latéral : le relâchement de Retour / MENU n'atteint pas l'option qui a le focus.
            if (sidePanelOpen && (code == KeyEvent.KEYCODE_BACK || code in TRACK_KEYS)) return true
            return super.dispatchKeyEvent(event)
        }
        if (routeKey(event)) {
            consumedKeys += code
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** État de l'interface qui reçoit les touches (un seul calcul, cf. [PlayerKeys.state]). */
    private fun uiState(ignoreChannelList: Boolean = false): UiState = PlayerKeys.state(
        UiFlags(
            panel = panelKind,
            channelListOpen = chListOpen && !ignoreChannelList,
            pauseScreenShown = pauseScreen.isShown,
            overlayFull = overlay.visible && overlay.mode == ControlOverlay.Mode.FULL,
            overlayVisible = overlay.visible,
            overlayFocusInside = overlay.hasFocusInside,
            barFocused = overlay.barFocused,
            nextCardVisible = nextCardVisible,
            touch = touch,
        )
    )

    private fun keyInput(code: Int): KeyInput {
        val item = current
        val live = item?.live == true
        return KeyInput(
            code = code,
            live = live,
            catchup = catchupOverride != null,
            liveControl = live && liveControl(),
            overlayVisible = overlay.visible,
            touch = touch,
        )
    }

    /**
     * Aiguillage d'un ACTION_DOWN : état de l'interface → action ([PlayerKeys.route]) → exécution. true : touche
     * consommée (son ACTION_UP le sera aussi) ; false : traitement standard (vue qui a le focus, système).
     */
    private fun routeKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        var state = uiState()
        if (state == UiState.CHANNEL_LIST) {
            armPauseScreen()
            // Liste des chaînes ouverte : elle reçoit les touches en priorité.
            if (handleChannelListKey(event)) return true
            state = uiState(ignoreChannelList = true)
        }
        var action = PlayerKeys.route(state, keyInput(code))
        if (action == KeyAction.PAUSE_SCREEN_DISMISS) {
            // Écran de pause : une autre touche le masque, puis suit son cours habituel.
            pauseScreen.hide()
            hideOverlay()
            state = uiState()
            action = PlayerKeys.route(state, keyInput(code))
        }
        if (action != KeyAction.PAUSE_SCREEN_RESUME && action != KeyAction.PAUSE_SCREEN_EXIT) armPauseScreen()
        // Commandes affichées : toute touche (sauf Retour) repousse leur masquage.
        if (PlayerKeys.isOverlayState(state) && code != KeyEvent.KEYCODE_BACK) scheduleOverlayHide()
        if (state == UiState.ERROR_PANEL && (action == KeyAction.ZAP_UP || action == KeyAction.ZAP_DOWN)) {
            // Chaîne morte : le panneau d'erreur s'efface, l'aperçu du zapping prend sa place.
            hidePanel()
        }
        return perform(action, event)
    }

    /** Exécute [action] (corps des gestionnaires d'origine). */
    private fun perform(action: KeyAction, event: KeyEvent): Boolean {
        val repeat = event.repeatCount
        when (action) {
            KeyAction.PASS -> return false
            KeyAction.CONSUME -> return true
            KeyAction.CLOSE_SIDE_PANEL -> { if (repeat == 0) hidePanel(); return true }
            KeyAction.CHANNEL_LIST_KEY -> return handleChannelListKey(event)
            KeyAction.PAUSE_SCREEN_RESUME -> {
                if (repeat == 0) { pauseScreen.hide(); togglePlayPause(true); showOverlay(OverlayFocus.PLAY) }
                return true
            }
            KeyAction.PAUSE_SCREEN_EXIT -> {
                if (repeat == 0) {
                    // Retour : tout est masqué d'un coup (un second Retour quitte le lecteur) ; ◀ ▶ ▲ ▼ : commandes.
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                        pauseScreen.hide()
                        cancelPendingSeeks()
                        hideOverlay()
                    } else exitPauseScreen()
                }
                return true
            }
            KeyAction.PAUSE_SCREEN_DISMISS -> { pauseScreen.hide(); hideOverlay(); return false }
            KeyAction.CANCEL_SEEK_AND_HIDE -> { cancelPendingSeeks(); hideOverlay(); return true }
            // Tactile : Retour quitte le lecteur même commandes affichées.
            KeyAction.CANCEL_SEEK_HIDE_AND_PASS -> { cancelPendingSeeks(); hideOverlay(); return false }
            KeyAction.DISMISS_NEXT_CARD -> { nextCardDismissed = true; hideNextCard(); return true }
            KeyAction.BACK_TO_LIVE -> { if (repeat == 0) backToLive(); return true }
            KeyAction.HIDE_OVERLAY -> { cancelPendingSeeks(); hideOverlay(); return true }
            KeyAction.OPEN_TRACKS -> { if (repeat == 0) showTracksPanel(); return true }
            KeyAction.ZAP_UP -> { zap(1, repeat); return true }
            KeyAction.ZAP_DOWN -> { zap(-1, repeat); return true }
            KeyAction.LAST_CHANNEL -> { if (repeat == 0) toggleLastChannel(); return true }
            KeyAction.DIGIT -> { if (repeat == 0) PlayerKeys.digitOf(event.keyCode)?.let { typeDigit(it) }; return true }
            else -> {}
        }
        val item = current ?: return false
        when (action) {
            KeyAction.SHOW_INFO -> {
                if (repeat == 0) {
                    handler.removeCallbacks(commitZap)
                    pendingZapIndex = null
                    showOverlay(durationMs = 8_000)
                    requestEpg(item.itemId)
                }
            }
            KeyAction.FOCUS_PLAY_PAUSE -> overlay.focusPlayPause()
            KeyAction.FOCUS_BAR -> overlay.focusBar()
            KeyAction.BAR_SEEK_BACK, KeyAction.BAR_SEEK_FWD -> {
                val forward = action == KeyAction.BAR_SEEK_FWD
                if (item.live) liveBarKey(forward, repeat) else seekStep(forward, repeat)
            }
            KeyAction.BAR_OK -> {
                if (repeat == 0 && !commitPendingSeek()) {
                    if (item.live) liveTogglePause() else togglePlayPause()
                }
            }
            KeyAction.LIVE_PLAY_PAUSE -> if (repeat == 0) { liveTogglePause(); showOverlay() }
            KeyAction.LIVE_PLAY -> if (repeat == 0) { liveTogglePause(true); showOverlay() }
            KeyAction.LIVE_PAUSE -> if (repeat == 0) { liveTogglePause(false); showOverlay() }
            KeyAction.LIVE_REWIND -> { showOverlay(OverlayFocus.BAR); liveSeekStep(false, repeat, 30_000) }
            KeyAction.LIVE_FAST_FORWARD -> { showOverlay(OverlayFocus.BAR); liveSeekStep(true, repeat, 30_000) }
            KeyAction.GO_LIVE -> if (repeat == 0) goLive()
            KeyAction.RESTART_PROGRAM -> if (repeat == 0) restartProgram()
            KeyAction.OPEN_CHANNEL_LIST -> if (repeat == 0) openChannelList()
            KeyAction.LIVE_SEEK_BACK, KeyAction.LIVE_SEEK_FWD -> {
                showOverlay(OverlayFocus.BAR)
                liveBarKey(action == KeyAction.LIVE_SEEK_FWD, repeat)
            }
            KeyAction.SOURCE_PREV -> if (repeat == 0) switchSource(-1)
            KeyAction.SOURCE_NEXT -> if (repeat == 0) switchSource(1)
            // OK : pause / lecture et commandes (sans contrôle du direct : commandes seules).
            KeyAction.LIVE_OK -> if (repeat == 0) {
                if (liveControl()) liveTogglePause()
                showOverlay(OverlayFocus.PLAY)
            }
            KeyAction.PLAY_NEXT_EPISODE -> if (repeat == 0) playNextEpisode()
            KeyAction.VOD_REWIND -> { showOverlay(OverlayFocus.BAR); seekStep(false, repeat, 30_000) }
            KeyAction.VOD_FAST_FORWARD -> { showOverlay(OverlayFocus.BAR); seekStep(true, repeat, 30_000) }
            KeyAction.VOD_PLAY_PAUSE -> if (repeat == 0) { togglePlayPause(); showOverlay() }
            KeyAction.VOD_PLAY -> if (repeat == 0) { togglePlayPause(true); showOverlay() }
            KeyAction.VOD_PAUSE -> if (repeat == 0) { togglePlayPause(false); showOverlay() }
            KeyAction.NEXT_EPISODE -> {
                if (repeat == 0 && item.type == Progress.EPISODE && PlayerSession.index + 1 < PlayerSession.queue.size) playNextEpisode()
            }
            KeyAction.SEEK_TO_START -> {
                if (repeat == 0) { handler.removeCallbacks(commitSeek); seekTarget = null; player.seekTo(0); showOverlay() }
            }
            KeyAction.SEEK_BACK -> { showOverlay(OverlayFocus.BAR); seekStep(false, repeat) }
            KeyAction.SEEK_FWD -> { showOverlay(OverlayFocus.BAR); seekStep(true, repeat) }
            KeyAction.VOD_OK -> if (repeat == 0) { togglePlayPause(); showOverlay(OverlayFocus.PLAY) }
            KeyAction.SHOW_OVERLAY_BUTTONS -> if (repeat == 0) showOverlay(OverlayFocus.PLAY)
            KeyAction.SHOW_OVERLAY_BAR -> if (repeat == 0) showOverlay(OverlayFocus.BAR)
            KeyAction.SHOW_OVERLAY -> showOverlay()
            else -> return false
        }
        return true
    }

    /** Déplacement en préparation (◀ ▶ cumulés) abandonné. */
    private fun cancelPendingSeeks() {
        handler.removeCallbacks(commitSeek)
        seekTarget = null
        seekDelta = 0
        cancelLiveSeek()
    }

    /** ◀ / ▶ sur la barre du direct : ±10 s cumulés ; ▶ maintenu : retour au direct. */
    private fun liveBarKey(forward: Boolean, repeat: Int) {
        if (!forward) { liveSeekStep(false, repeat, 10_000); return }
        if (repeat == 0) {
            goLiveHeld = false
            liveSeekStep(true, 0, 10_000)
        } else if (repeat >= 2 && !goLiveHeld) {
            goLiveHeld = true
            goLive()
            overlay.focusBar()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Téléphone / tablette : plein écran immersif, barre du haut, gestes
    // ---------------------------------------------------------------------------------------------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (touch && hasFocus) enterImmersive()
    }

    /** Barres système masquées (réapparaissent temporairement d'un glissement depuis le bord). */
    private fun enterImmersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * Gestes sur l'image : appui = afficher / masquer les commandes (ou fermer le panneau ouvert),
     * double appui à gauche / à droite = -10 s / +10 s (hors direct), glissement vertical à gauche =
     * luminosité, à droite = volume.
     */
    private fun installTouchGestures() {
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean {
                swipeMode = 0
                return true
            }

            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                when {
                    pauseScreen.isShown -> exitPauseScreen()
                    sidePanelOpen -> hidePanel()
                    chListOpen -> closeChannelList()
                    panelKind != PanelKind.NONE -> Unit
                    overlay.visible && overlay.mode == ControlOverlay.Mode.FULL -> hideOverlay()
                    else -> showOverlay()
                }
                return true
            }

            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                val item = current ?: return false
                if (panelKind != PanelKind.NONE || chListOpen) return false
                val forward = e.x > playerView.width / 2f
                if (item.live) {
                    // Direct enregistré : -10 s / +10 s dans le tampon (appuis cumulés).
                    if (!liveControl()) return false
                    showOverlay()
                    liveSeekStep(forward, 0, 10_000)
                    return true
                }
                // Appuis cumulés comme avec ◀ / ▶ (cible affichée sur la barre, appliquée après une courte pause).
                showOverlay()
                seekStep(forward, 0)
                return true
            }

            override fun onScroll(
                e1: android.view.MotionEvent?, e2: android.view.MotionEvent, distanceX: Float, distanceY: Float,
            ): Boolean {
                val start = e1 ?: return false
                if (panelKind != PanelKind.NONE || chListOpen) return false
                val h = playerView.height.takeIf { it > 0 } ?: return false
                if (swipeMode == 0) {
                    val dy = start.y - e2.y
                    val dx = start.x - e2.x
                    if (abs(dy) < dp(24) || abs(dy) < abs(dx) * 1.5f) return false
                    // Bords haut / bas : laissés au système (barres).
                    if (start.y < dp(40) || start.y > h - dp(40)) return false
                    if (start.x < playerView.width / 2f) {
                        swipeMode = 1
                        val cur = window.attributes.screenBrightness
                        swipeStartValue = if (cur in 0f..1f) cur else 0.5f
                    } else {
                        swipeMode = 2
                        swipeStartValue = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()
                    }
                }
                val frac = (start.y - e2.y) / (h * 0.7f)
                if (swipeMode == 1) {
                    val v = (swipeStartValue + frac).coerceIn(0.02f, 1f)
                    window.attributes = window.attributes.apply { screenBrightness = v }
                    showPill("☀  Luminosité ${(v * 100).toInt()} %", 800)
                } else if (swipeMode == 2) {
                    val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val v = (swipeStartValue + frac * max).toInt().coerceIn(0, max)
                    runCatching { audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0) }
                    showPill("🔊  Volume ${if (max > 0) v * 100 / max else 0} %", 800)
                }
                return true
            }
        })
        // Tous les touchers de l'image passent par le détecteur (PlayerView ne bascule plus lui-même
        // le contrôleur ; les boutons du contrôleur reçoivent toujours leurs touchers).
        @Suppress("ClickableViewAccessibility")
        playerView.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }
    }

    // ---------------------------------------------------------------------------------------------
    // Habillage (cartes arrondies)
    // ---------------------------------------------------------------------------------------------

    private fun card(color: Int = CARD, radiusDp: Float = PlayerUi.RADIUS) = PlayerUi.rounded(this, color, radiusDp)

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false) = PlayerUi.text(this, sizeSp, color, bold)

    /** Pastille de message : une ligne discrète, en bas au centre. */
    private fun buildPill(root: FrameLayout) {
        pill = text(15f, Color.WHITE).apply {
            background = card(radiusDp = 20f)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        root.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(48)
        })
    }

    private fun buildDigits(root: FrameLayout) {
        digitsView = PlayerUi.tabular(PlayerUi.text(this, 34f, Color.WHITE, PlayerUi.Weight.SEMIBOLD)).apply {
            background = card()
            setPadding(dp(24), dp(14), dp(24), dp(14))
            visibility = View.GONE
        }
        root.addView(digitsView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(48); topMargin = dp(36)
        })
    }

    private fun buildPanel(root: FrameLayout) {
        // Panneau latéral droit : audio et sous-titres, qualité et image.
        sidePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card(PlayerUi.PANEL)
            setPadding(dp(if (touch) 18 else 28), dp(if (touch) 18 else 28), dp(if (touch) 18 else 28), dp(if (touch) 14 else 24))
            visibility = View.GONE
            isClickable = true
        }
        // Taille selon le contenu, à droite, centré verticalement.
        root.addView(sidePanel, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            val m = dp(if (touch) 12 else 24)
            leftMargin = m; rightMargin = m; topMargin = m; bottomMargin = m
        })
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = card()
            setPadding(dp(36), dp(28), dp(36), dp(28))
            visibility = View.GONE
            isClickable = true // n'envoie pas les clics au lecteur en dessous
        }
        if (touch) {
            // Téléphone en paysage (écran peu haut) : le panneau défile. Hauteur « wrap » : panneau masqué,
            // la vue de défilement ne mesure rien et ne capte aucun toucher.
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isFillViewport = false
                addView(panel, FrameLayout.LayoutParams(-2, -2))
            }
            root.addView(scroll, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
                leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(12); bottomMargin = dp(12)
            })
            return
        }
        // TV : marges réduites pour que la 3e colonne (Qualité) ne soit pas tronquée (« Source 1… »).
        root.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
            leftMargin = dp(if (touch) 120 else 56); rightMargin = dp(if (touch) 120 else 56)
        })
    }

    private fun showPill(msg: String, durationMs: Long = 3_000, sticky: Boolean = false) {
        pill.text = msg
        pill.visibility = View.VISIBLE
        handler.removeCallbacks(hidePill)
        if (!sticky) handler.postDelayed(hidePill, durationMs)
    }

    private fun hidePillNow() {
        handler.removeCallbacks(hidePill)
        pill.visibility = View.GONE
    }

    private fun showResumePrompt(positionMs: Long) {
        val item = current ?: return
        showPanel(
            PanelKind.RESUME,
            "Reprendre la lecture ?",
            listOfNotNull(item.title, episodeLine(item)).joinToString("  ·  "),
            listOf(
                "Reprendre à ${PlayerTime.clock(positionMs)}" to { hidePanel(); player.play() },
                "Depuis le début" to { exactResumeKey = null; exactResumeGen++; hidePanel(); player.seekTo(0); player.play() },
            ),
            0,
            icons = listOf(PlayerIcon.PLAY, PlayerIcon.RESTART),
        )
    }

    /** Panneau centré (reprise, erreur) : titre, message, boutons au style des commandes. */
    private fun showPanel(
        kind: PanelKind,
        title: String,
        message: String?,
        buttons: List<Pair<String, () -> Unit>>,
        focusIndex: Int,
        icons: List<PlayerIcon?> = emptyList(),
    ) {
        handler.removeCallbacks(retryRunnable)
        hideOverlay()
        closeChannelList(animate = false)
        sidePanel.visibility = View.GONE
        panel.removeAllViews()
        panel.addView(PlayerUi.text(this, 24f, Color.WHITE, PlayerUi.Weight.SEMIBOLD).apply {
            gravity = Gravity.CENTER; this.text = title
        })
        if (!message.isNullOrBlank()) {
            panel.addView(text(15f, SECONDARY).apply {
                gravity = Gravity.CENTER; this.text = message; maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (resources.displayMetrics.widthPixels * 0.6f).toInt()
                setPadding(0, dp(10), 0, 0)
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val views = buttons.mapIndexed { i, (label, action) ->
            LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                minimumWidth = dp(170)
                minimumHeight = dp(48)
                setPadding(dp(20), dp(10), dp(24), dp(10))
                background = PlayerUi.focusBackground(this@PlayerActivity, 100f, PlayerUi.SUBTLE)
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                setOnClickListener { action() }
                icons.getOrNull(i)?.let { ic ->
                    addView(ImageView(this@PlayerActivity).apply {
                        setImageDrawable(IconDrawable(ic, dp(22), PlayerUi.focusTextColors(), PlayerUi.font(this@PlayerActivity, PlayerUi.Weight.SEMIBOLD)))
                        isDuplicateParentStateEnabled = true
                    }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
                }
                addView(PlayerUi.text(this@PlayerActivity, 16f, Color.WHITE, PlayerUi.Weight.MEDIUM).apply {
                    this.text = label
                    setTextColor(PlayerUi.focusTextColors())
                    isDuplicateParentStateEnabled = true
                })
            }
        }
        views.forEachIndexed { i, v ->
            // Le focus reste dans le panneau.
            v.nextFocusUpId = v.id
            v.nextFocusDownId = v.id
            v.nextFocusLeftId = views.getOrNull(i - 1)?.id ?: v.id
            v.nextFocusRightId = views.getOrNull(i + 1)?.id ?: v.id
            row.addView(v, LinearLayout.LayoutParams(-2, -2).apply {
                if (i > 0) leftMargin = dp(14)
            })
        }
        panel.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })
        panelKind = kind
        panel.visibility = View.VISIBLE
        hideNextCard()
        hidePillNow()
        if (!touch) views.getOrNull(focusIndex)?.let { v -> v.post { v.requestFocus() } }
    }

    private fun hidePanel() {
        if (panelKind == PanelKind.NONE) return
        panelKind = PanelKind.NONE
        panel.visibility = View.GONE
        panel.removeAllViews()
        sidePanel.visibility = View.GONE
        sidePanel.removeAllViews()
        armPauseScreen()
    }

    // ---------------------------------------------------------------------------------------------
    // Liste des chaînes (direct) : panneau gauche ouvert par ▲ / ▼
    // ---------------------------------------------------------------------------------------------

    private fun buildChannelList(root: FrameLayout) {
        val dm = resources.displayMetrics
        val margin = dp(20)
        chListWidth = (dm.widthPixels * 0.32f).toInt().coerceAtLeast(dp(300))
        val title = PlayerUi.text(this, 22f, Color.WHITE, PlayerUi.Weight.SEMIBOLD).apply { text = "Chaînes" }
        chCount = text(13f, SECONDARY)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(10))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(chCount)
        }
        val rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val footer = text(12f, SECONDARY).apply {
            text = "OK : regarder · ◀ : fermer"
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        // Nombre de lignes visibles selon la hauteur de l'écran (~54 dp par ligne).
        val avail = dm.heightPixels - 2 * margin - dp(28) - dp(36) - dp(28)
        val rowCount = (avail / dp(54)).coerceIn(5, 12)
        repeat(rowCount) { r ->
            val num = text(16f, Color.WHITE, bold = true).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                maxLines = 1
            }
            val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val name = text(15f, Color.WHITE, bold = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            val prog = text(12f, SECONDARY).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            }
            val fill = View(this)
            val rest = View(this)
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = card(0x33FFFFFF, 2f)
                weightSum = 1000f
                addView(fill, LinearLayout.LayoutParams(0, -1, 0f))
                addView(rest, LinearLayout.LayoutParams(0, -1, 1000f))
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(name)
                addView(prog)
                addView(bar, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(4) })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, dp(12), 0)
                addView(num, LinearLayout.LayoutParams(dp(42), -2))
                addView(logo, LinearLayout.LayoutParams(dp(52), dp(34)).apply { leftMargin = dp(10) })
                addView(col, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(12) })
            }
            rowsBox.addView(row, LinearLayout.LayoutParams(-1, 0, 1f).apply { if (r > 0) topMargin = dp(3) })
            chRows += ChRow(row, num, logo, name, prog, bar, fill, rest)
            if (touch) {
                // Tactile : appui sur une ligne = regarder cette chaîne.
                row.isClickable = true
                row.setOnClickListener {
                    val target = chTop + r
                    if (target !in PlayerSession.queue.indices) return@setOnClickListener
                    closeChannelList()
                    if (target != PlayerSession.index) goToChannel(target)
                }
            }
        }
        if (touch) footer.text = "Touche une chaîne pour la regarder · glisse pour faire défiler"
        // Tactile : un glissement vertical sur la liste la fait défiler (ligne par ligne).
        chList = object : LinearLayout(this) {
            private var downY = 0f
            private var lastY = 0f
            private var dragging = false
            private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

            override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
                if (!touch) return false
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downY = ev.y; lastY = ev.y; dragging = false
                        bumpChannelListTimer()
                    }
                    android.view.MotionEvent.ACTION_MOVE -> if (!dragging && abs(ev.y - downY) > slop) {
                        dragging = true
                        lastY = ev.y
                    }
                }
                return dragging
            }

            @Suppress("ClickableViewAccessibility")
            override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
                if (!touch) return super.onTouchEvent(ev)
                if (ev.actionMasked == android.view.MotionEvent.ACTION_MOVE && dragging) {
                    val step = dp(52).toFloat()
                    while (ev.y - lastY >= step) { lastY += step; moveChannelSel(-1) }
                    while (lastY - ev.y >= step) { lastY -= step; moveChannelSel(1) }
                    bumpChannelListTimer()
                }
                if (ev.actionMasked == android.view.MotionEvent.ACTION_UP ||
                    ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL
                ) dragging = false
                return true
            }
        }
        chList.apply {
            orientation = LinearLayout.VERTICAL
            background = card(PlayerUi.PANEL)
            setPadding(dp(12), dp(16), dp(12), dp(14))
            visibility = View.GONE
            isClickable = true
            addView(header)
            addView(rowsBox, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(chList, FrameLayout.LayoutParams(chListWidth, -1, Gravity.START or Gravity.TOP).apply {
            leftMargin = margin; topMargin = margin; bottomMargin = margin
        })
    }

    private fun openChannelList() {
        if (PlayerSession.queue.isEmpty()) return
        handler.removeCallbacks(commitZap)
        chSel = (pendingZapIndex ?: PlayerSession.index).coerceIn(0, PlayerSession.queue.lastIndex)
        pendingZapIndex = null
        chTop = chSel - chRows.size / 2
        chEpgRequested.clear()
        hideOverlay()
        hidePillNow()
        chListOpen = true
        previewSel = -1
        renderChannelList()
        chList.animate().cancel()
        if (chList.visibility != View.VISIBLE) {
            chList.translationX = -(chListWidth + dp(20)).toFloat()
            chList.alpha = 0f
            chList.visibility = View.VISIBLE
        }
        chList.animate().translationX(0f).alpha(1f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
        bumpChannelListTimer()
    }

    private fun closeChannelList(animate: Boolean = true) {
        if (!::chList.isInitialized || !chListOpen) return
        chListOpen = false
        handler.removeCallbacks(hideChannelList)
        handler.removeCallbacks(chEpgFetch)
        // L'aperçu (second flux) est coupé avant tout changement de chaîne.
        handler.removeCallbacks(previewRunnable)
        previewSel = -1
        preview.hide()
        chList.animate().cancel()
        if (!animate) {
            chList.visibility = View.GONE
            return
        }
        chList.animate().translationX(-(chListWidth + dp(20)).toFloat()).alpha(0f).setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!chListOpen) chList.visibility = View.GONE }
            .start()
    }

    private fun bumpChannelListTimer() {
        handler.removeCallbacks(hideChannelList)
        handler.postDelayed(hideChannelList, CH_LIST_TIMEOUT_MS)
    }

    private fun moveChannelSel(delta: Int) {
        val size = PlayerSession.queue.size
        if (size == 0) return
        chSel = ((chSel + delta) % size + size) % size // boucle en haut / en bas
        renderChannelList()
    }

    /** Touches quand la liste est ouverte. false : touche non prise en charge (traitement normal). */
    private fun handleChannelListKey(event: KeyEvent): Boolean {
        val repeat = event.repeatCount
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveChannelSel(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveChannelSel(1)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> moveChannelSel(-chRows.size)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> moveChannelSel(chRows.size)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (repeat == 0) {
                    val target = chSel
                    closeChannelList()
                    goToChannel(target)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK -> {
                if (repeat == 0) closeChannelList()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {}
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return false
            else -> {
                // Chiffres, MENU, INFO… : on ferme la liste et la touche suit son cours habituel.
                closeChannelList(animate = false)
                return false
            }
        }
        bumpChannelListTimer()
        return true
    }

    private fun renderChannelList() {
        val q = PlayerSession.queue
        val size = q.size
        if (size == 0 || chRows.isEmpty()) return
        val rows = chRows.size
        chSel = chSel.coerceIn(0, size - 1)
        if (size <= rows) chTop = 0 else {
            if (chSel < chTop) chTop = chSel
            if (chSel >= chTop + rows) chTop = chSel - rows + 1
            chTop = chTop.coerceIn(0, size - rows)
        }
        chCount.text = "${chSel + 1} / $size"
        val now = System.currentTimeMillis()
        val visible = HashSet<String>()
        chRows.forEachIndexed { r, row ->
            val i = chTop + r
            val item = q.getOrNull(i)
            if (item == null) {
                row.root.visibility = View.INVISIBLE
                return@forEachIndexed
            }
            row.root.visibility = View.VISIBLE
            visible += item.itemId
            val selected = i == chSel
            val playing = i == PlayerSession.index
            // Même langage que les commandes : ligne sélectionnée = fond blanc, texte noir.
            row.root.background = when {
                selected -> card(Color.WHITE, 10f)
                playing -> card(PlayerUi.SUBTLE, 10f)
                else -> null
            }
            row.num.text = numberOf(i).toString()
            row.num.setTextColor(if (selected) Color.BLACK else if (playing) ACCENT else Color.WHITE)
            row.name.setTextColor(if (selected) Color.BLACK else Color.WHITE)
            row.name.text = if (playing) "${item.title}  ●" else item.title
            val url = item.poster?.takeIf { it.isNotBlank() }
            if (row.logoUrl != url) {
                row.logoUrl = url
                if (url == null) {
                    row.logo.dispose()
                    row.logo.setImageDrawable(null)
                } else {
                    row.logo.load(url) {
                        crossfade(false)
                        size(dp(104), dp(68))
                    }
                }
            }
            val cur = Epg.cached(item.itemId).firstOrNull { it.isNow(now) }
            if (cur != null) {
                row.prog.text = "${hourFormat.format(Date(cur.startMs))}  ${cur.title}"
                row.prog.setTextColor(if (selected) 0xFF4A4F5C.toInt() else SECONDARY)
                val p = (cur.progress(now) * 1000).toInt().coerceIn(0, 1000)
                (row.fill.layoutParams as LinearLayout.LayoutParams).weight = p.toFloat()
                (row.rest.layoutParams as LinearLayout.LayoutParams).weight = (1000 - p).toFloat()
                row.fill.background = card(ACCENT, 2f)
                row.bar.background = card(if (selected) 0x33000000 else 0x33FFFFFF, 2f)
                row.bar.requestLayout()
                row.prog.visibility = View.VISIBLE
                row.bar.visibility = View.VISIBLE
            } else {
                row.prog.visibility = View.GONE
                row.bar.visibility = View.GONE
            }
        }
        chVisibleIds = visible
        handler.removeCallbacks(chEpgFetch)
        handler.postDelayed(chEpgFetch, 250)
        if (chSel != previewSel) {
            previewSel = chSel
            scheduleChannelPreview()
        } else if (preview.infoShown) {
            // Guide arrivé entre-temps : la fiche est mise à jour.
            q.getOrNull(chSel)?.let { preview.showInfo(it, numberOf(chSel), Epg.cached(it.itemId)) }
        }
    }

    /** Abonnement à 2 connexions ou plus : aperçu vidéo ; sinon (ou inconnu) fiche du programme. */
    private val previewVideo: Boolean get() = ProviderConnections.allowsExtra(maxConnections)

    private fun scheduleChannelPreview() {
        handler.removeCallbacks(previewRunnable)
        if (previewVideo) {
            // Le second flux est coupé dès que la sélection bouge.
            preview.hide()
            if (chSel != PlayerSession.index) handler.postDelayed(previewRunnable, PREVIEW_DELAY_MS)
        } else {
            handler.postDelayed(previewRunnable, INFO_CARD_DELAY_MS)
        }
    }

    private fun showChannelPreview() {
        if (!chListOpen) return
        val idx = chSel
        val item = PlayerSession.queue.getOrNull(idx) ?: return
        if (previewVideo) {
            if (idx == PlayerSession.index) return
            preview.showVideo(item, numberOf(idx))
            return
        }
        preview.showInfo(item, numberOf(idx), Epg.cached(item.itemId))
        val id = item.itemId
        if (id.isBlank() || Epg.cached(id).any { it.isNow() }) return
        val ctx = applicationContext
        runCatching {
            epgExecutor.execute {
                val list = runCatching { Epg.nowNext(ctx, id, 2) }.getOrElse { emptyList() }
                handler.post {
                    if (isDestroyed || !chListOpen || chSel != idx || preview.shownId != id) return@post
                    preview.showInfo(item, numberOf(idx), list)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Séries : carte « Épisode suivant »
    // ---------------------------------------------------------------------------------------------

    /**
     * Carte « Épisode suivant dans 12 s » : vignette, titre de l'épisode, « Regarder » (OK) et « Annuler »
     * (Retour). Les boutons ne prennent pas le focus (TV) : OK / Retour leur sont routés.
     */
    private fun buildNextCard(root: FrameLayout) {
        nextCardThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1D212D.toInt())
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpf(10f))
                }
            }
            clipToOutline = true
        }
        nextCardCountdown = PlayerUi.tabular(PlayerUi.text(this, 14f, SECONDARY, PlayerUi.Weight.MEDIUM))
        nextCardSubtitle = PlayerUi.text(this, 17f, Color.WHITE, PlayerUi.Weight.SEMIBOLD).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(300)
            setPadding(0, dp(6), 0, 0)
        }
        fun cardButton(label: String, icon: PlayerIcon?, primary: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(40)
            setPadding(dp(16), dp(8), dp(18), dp(8))
            background = card(if (primary) Color.WHITE else PlayerUi.SUBTLE, 100f)
            isClickable = true
            setOnClickListener { onClick() }
            val color = if (primary) Color.BLACK else Color.WHITE
            if (icon != null) addView(ImageView(this@PlayerActivity).apply {
                setImageDrawable(IconDrawable(icon, dp(18), android.content.res.ColorStateList.valueOf(color), PlayerUi.font(this@PlayerActivity, PlayerUi.Weight.SEMIBOLD)))
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) })
            addView(PlayerUi.text(this@PlayerActivity, 15f, color, PlayerUi.Weight.MEDIUM).apply { text = label })
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cardButton("Regarder", PlayerIcon.PLAY, true) { playNextEpisode() })
            addView(cardButton("Annuler", null, false) { nextCardDismissed = true; hideNextCard() },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nextCardCountdown)
            addView(nextCardSubtitle)
            addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        }
        nextCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = card()
            setPadding(dp(14), dp(14), dp(20), dp(14))
            visibility = View.GONE
            addView(nextCardThumb, LinearLayout.LayoutParams(dp(160), dp(90)))
            addView(col, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16) })
        }
        root.addView(nextCard, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(if (touch) 16 else 48); bottomMargin = dp(if (touch) 16 else 48)
        })
    }

    private fun nextEligible(pos: Long): Boolean {
        val item = current ?: return false
        if (item.live || item.type != Progress.EPISODE || nextCardDismissed) return false
        if (PlayerSession.index + 1 >= PlayerSession.queue.size) return false
        val d = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return false
        return d > 180_000 && pos >= d - NEXT_THRESHOLD_MS
    }

    /** Affiche / masque la carte selon la position (toutes les 500 ms et après chaque saut). */
    private fun updateNextCard(posOverride: Long? = null) {
        if (!::nextCard.isInitialized) return
        val pos = posOverride ?: seekTarget ?: player.currentPosition
        // Masquée quand les commandes sont affichées (elles proposent « Épisode suivant »).
        val show = panelKind == PanelKind.NONE && !chListOpen && !overlay.visible &&
            player.playbackState != Player.STATE_IDLE && nextEligible(pos)
        if (!show) { hideNextCard(); return }
        val d = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val secs = ((d - pos).coerceAtLeast(0) + 999) / 1000
        nextCardCountdown.text = "Épisode suivant dans $secs s"
        if (nextCardVisible) return
        val next = PlayerSession.queue.getOrNull(PlayerSession.index + 1)
        val sub = next?.let { n ->
            if (n.season > 0 || n.episode > 0) {
                "S${n.season} · É${n.episode}" + (cleanEpisodeTitle(n.episodeTitle, n.title)?.let { " · $it" } ?: "")
            } else n.subtitle?.takeIf { it.isNotBlank() } ?: n.title
        }
        nextCardSubtitle.text = sub ?: ""
        nextCardSubtitle.visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE
        val thumb = next?.poster?.takeIf { it.isNotBlank() }
        if (thumb != null) { nextCardThumb.visibility = View.VISIBLE; nextCardThumb.load(thumb) { crossfade(false) } }
        else { nextCardThumb.dispose(); nextCardThumb.visibility = View.GONE }
        nextCardVisible = true
        nextCard.visibility = View.VISIBLE
    }

    private fun hideNextCard() {
        if (!::nextCard.isInitialized) return
        nextCardVisible = false
        nextCard.visibility = View.GONE
    }

    private fun playNextEpisode() {
        if (PlayerSession.index + 1 >= PlayerSession.queue.size) return
        handler.removeCallbacks(commitSeek)
        seekTarget = null
        seekDelta = 0
        hideNextCard()
        saveProgress(finished = true)
        PlayerSession.index++
        playCurrent()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(stopRecordingRunnable)
        // Retour dans le lecteur : l'enregistrement du direct reprend dans le même tampon.
        if (tsActive) ts?.resumeRecording()
    }

    override fun onStop() {
        super.onStop()
        // Direct enregistré : on n'enregistre pas en arrière-plan plus de 30 s.
        handler.removeCallbacks(stopRecordingRunnable)
        if (ts != null) handler.postDelayed(stopRecordingRunnable, 30_000)
        if (::player.isInitialized) {
            saveProgress(sync = true)
            player.pause()
            // Google Drive : reprises envoyées 10 s après la fin de lecture (profil lié uniquement).
            runCatching { com.fluxfr.tv.data.DriveSync.requestSync(this, delayMs = 10_000) }
        }
        if (::preview.isInitialized) preview.release()
        if (::afr.isInitialized) afr.restore()
        if (!isChangingConfigurations) finish()
    }

    override fun finish() {
        if (::afr.isInitialized) afr.restore()
        super.finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // Fin du lecteur : enregistrement arrêté et tampon effacé.
        ts?.release()
        ts = null
        epgExecutor.shutdownNow()
        infoExecutor.shutdownNow()
        if (::preview.isInitialized) preview.release()
        if (::thumbs.isInitialized) thumbs.release()
        night.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private companion object {
        const val CARD = PlayerUi.CARD
        const val SECONDARY = PlayerUi.SECONDARY
        const val ACCENT = PlayerUi.ACCENT
        const val OVERLAY_TIMEOUT_MS = 4_000L
        const val START_OVERLAY_MS = 3_000L
        /** Délai avant d'appliquer un déplacement (◀ ▶ cumulés) ; OK l'applique tout de suite. */
        const val SEEK_COMMIT_MS = 800L
        /** Boutons placés à droite de la rangée (actions), les autres (⏪ ⏯ ⏩) à gauche. */
        val RIGHT_BUTTONS = setOf("next", "restart", "live", "channels", "tracks", "quality")
        val UNKNOWN_LANGS = setOf("", "und", "unk", "mis", "mul", "zxx", "qaa")
        const val NEXT_THRESHOLD_MS = 40_000L
        const val TRACK_PREFS = "fluxfr_tracks"
        const val TEXT_OFF = "off"
        const val KEY_HINT_COUNT = "hintCount"
        const val KEY_LIST_HINT_COUNT = "listHintCount"
        const val CH_LIST_TIMEOUT_MS = 10_000L
        const val APP_PREFS = "fluxfr"
        const val KEY_AFR = "afr"
        const val KEY_NIGHT = "nightMode"
        const val ZAP_TAG = "FluxZap"
        const val PAUSE_SCREEN_DELAY_MS = 5_000L
        const val PREVIEW_DELAY_MS = 700L
        const val INFO_CARD_DELAY_MS = 150L
        val BITSTREAM_AUDIO = setOf(
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC4,
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_TRUEHD,
        )
        /** Code d'épisode dans un titre (« S01E03 », « 1x03 »). */
        val EPISODE_CODE = Regex("""(?i)\bS\d{1,2}\s*[.\-]?\s*E\d{1,3}\b|\b\d{1,2}x\d{2,3}\b""")
        val EPISODE_ONLY = Regex("""(?i)^(é|e)pisode\s*\d+$""")
        val TRACK_KEYS = PlayerKeys.TRACK_KEYS
        /** Codes ISO 639-2/B → 639-2/T. */
        val BIBLIO_LANGS = mapOf(
            "fre" to "fra", "ger" to "deu", "dut" to "nld", "chi" to "zho", "cze" to "ces", "gre" to "ell",
            "per" to "fas", "rum" to "ron", "slo" to "slk", "alb" to "sqi", "arm" to "hye", "baq" to "eus",
            "bur" to "mya", "geo" to "kat", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa",
            "tib" to "bod", "wel" to "cym",
        )
        val ISO3_TO_2: Map<String, String> by lazy {
            Locale.getISOLanguages().mapNotNull { l ->
                runCatching { Locale(l).isO3Language to l }.getOrNull()?.takeIf { it.first.isNotEmpty() }
            }.toMap()
        }
    }
}
