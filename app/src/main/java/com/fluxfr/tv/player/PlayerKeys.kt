package com.fluxfr.tv.player

import android.view.KeyEvent

/** Panneau ouvert dans le lecteur. */
internal enum class PanelKind { NONE, RESUME, ERROR, TRACKS, QUALITY }

/**
 * Élément de l'interface du lecteur qui reçoit les touches, du plus prioritaire au moins prioritaire.
 * Calculé en un seul endroit ([PlayerKeys.state]) à partir des indicateurs de l'activité.
 */
internal enum class UiState {
    ERROR_PANEL,
    RESUME_PANEL,
    TRACKS_PANEL,
    QUALITY_PANEL,
    CHANNEL_LIST,
    PAUSE_SCREEN,
    /** Commandes complètes affichées (TV), aucun élément n'a le focus (après un toucher, par exemple). */
    OVERLAY_NO_FOCUS,
    /** Commandes complètes affichées (TV), focus sur un bouton. */
    OVERLAY_BUTTONS,
    /** Commandes complètes affichées (TV), focus sur la barre de progression. */
    OVERLAY_BAR,
    /** Commandes complètes affichées (téléphone / tablette). */
    OVERLAY_TOUCH,
    /** Carte « Épisode suivant » affichée, commandes masquées. */
    NEXT_CARD,
    /** Rien d'affiché (ou seulement l'en-tête du zapping) : modèle façon Netflix. */
    OVERLAY_HIDDEN,
}

/** Indicateurs de l'activité dont dépend [UiState]. */
internal data class UiFlags(
    val panel: PanelKind = PanelKind.NONE,
    val channelListOpen: Boolean = false,
    val pauseScreenShown: Boolean = false,
    /** Commandes visibles en mode complet (titre, barre, boutons). */
    val overlayFull: Boolean = false,
    /** Commandes visibles, quel que soit le mode (complet, en-tête, pause). */
    val overlayVisible: Boolean = false,
    val overlayFocusInside: Boolean = false,
    val barFocused: Boolean = false,
    val nextCardVisible: Boolean = false,
    val touch: Boolean = false,
)

/** Contexte de lecture utile à l'aiguillage d'une touche. */
internal data class KeyInput(
    val code: Int,
    /** Chaîne en direct (hors replay). */
    val live: Boolean,
    /** Replay lancé depuis le direct. */
    val catchup: Boolean = false,
    /** Contrôle du direct possible (pause, retour en arrière). */
    val liveControl: Boolean = false,
    val overlayVisible: Boolean = false,
    val touch: Boolean = false,
)

/** Action déclenchée par une touche ; [PASS] : traitement standard (vue qui a le focus, système). */
internal enum class KeyAction {
    PASS,
    CONSUME,
    CLOSE_SIDE_PANEL,
    CHANNEL_LIST_KEY,
    PAUSE_SCREEN_RESUME,
    PAUSE_SCREEN_EXIT,
    /** Masque l'écran de pause, puis la touche suit son cours habituel. */
    PAUSE_SCREEN_DISMISS,
    /** Retour, commandes affichées : déplacement en préparation annulé, commandes masquées. */
    CANCEL_SEEK_AND_HIDE,
    /** Idem, puis Retour suit son cours (tactile : quitte le lecteur). */
    CANCEL_SEEK_HIDE_AND_PASS,
    DISMISS_NEXT_CARD,
    BACK_TO_LIVE,
    HIDE_OVERLAY,
    OPEN_TRACKS,
    ZAP_UP,
    ZAP_DOWN,
    LAST_CHANNEL,
    DIGIT,
    SHOW_INFO,
    FOCUS_PLAY_PAUSE,
    FOCUS_BAR,
    BAR_SEEK_BACK,
    BAR_SEEK_FWD,
    BAR_OK,
    LIVE_PLAY_PAUSE,
    LIVE_PLAY,
    LIVE_PAUSE,
    LIVE_REWIND,
    LIVE_FAST_FORWARD,
    GO_LIVE,
    RESTART_PROGRAM,
    OPEN_CHANNEL_LIST,
    LIVE_SEEK_BACK,
    LIVE_SEEK_FWD,
    SOURCE_PREV,
    SOURCE_NEXT,
    LIVE_OK,
    PLAY_NEXT_EPISODE,
    VOD_REWIND,
    VOD_FAST_FORWARD,
    VOD_PLAY_PAUSE,
    VOD_PLAY,
    VOD_PAUSE,
    NEXT_EPISODE,
    SEEK_TO_START,
    SEEK_BACK,
    SEEK_FWD,
    VOD_OK,
    SHOW_OVERLAY_BUTTONS,
    SHOW_OVERLAY_BAR,
    SHOW_OVERLAY,
}

/**
 * Aiguillage des touches de la télécommande (partie pure, testée sur la JVM).
 *
 * Commandes masquées : OK = pause + commandes, ◀ ▶ = ±10 s (barre affichée), ▼ = commandes (focus sur ⏯),
 * ▲ = commandes (focus sur la barre) ; en direct ▲ ▼ = liste des chaînes, CH+ / CH- et chiffres = zapping.
 * Commandes affichées : ◀ ▶ et OK sur les boutons sont laissés à la vue qui a le focus, ▲ vers la barre
 * (◀ ▶ s'y déplacent, OK valide), ▼ depuis la barre vers ⏯, Retour masque.
 */
internal object PlayerKeys {
    val VOLUME_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
    val DPAD_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )
    val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    val TRACK_KEYS = setOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK)

    private val OVERLAY_STATES = setOf(UiState.OVERLAY_NO_FOCUS, UiState.OVERLAY_BUTTONS, UiState.OVERLAY_BAR)

    fun isOverlayState(s: UiState) = s in OVERLAY_STATES

    fun state(f: UiFlags): UiState = when {
        f.panel == PanelKind.ERROR -> UiState.ERROR_PANEL
        f.panel == PanelKind.RESUME -> UiState.RESUME_PANEL
        f.panel == PanelKind.TRACKS -> UiState.TRACKS_PANEL
        f.panel == PanelKind.QUALITY -> UiState.QUALITY_PANEL
        f.channelListOpen -> UiState.CHANNEL_LIST
        f.pauseScreenShown -> UiState.PAUSE_SCREEN
        f.overlayFull && f.touch -> UiState.OVERLAY_TOUCH
        f.overlayFull && !f.overlayFocusInside -> UiState.OVERLAY_NO_FOCUS
        f.overlayFull && f.barFocused -> UiState.OVERLAY_BAR
        f.overlayFull -> UiState.OVERLAY_BUTTONS
        f.nextCardVisible && !f.overlayVisible -> UiState.NEXT_CARD
        else -> UiState.OVERLAY_HIDDEN
    }

    /** Chiffre (0-9, pavé numérique compris) ; null pour une autre touche. */
    fun digitOf(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

    fun route(state: UiState, k: KeyInput): KeyAction = when (state) {
        UiState.ERROR_PANEL -> errorPanel(k)
        // Navigation standard entre les boutons, Retour = quitter.
        UiState.RESUME_PANEL -> KeyAction.PASS
        UiState.TRACKS_PANEL, UiState.QUALITY_PANEL ->
            if (k.code == KeyEvent.KEYCODE_BACK || k.code in TRACK_KEYS) KeyAction.CLOSE_SIDE_PANEL else KeyAction.PASS
        UiState.CHANNEL_LIST -> KeyAction.CHANNEL_LIST_KEY
        UiState.PAUSE_SCREEN -> when {
            k.code in VOLUME_KEYS -> KeyAction.PASS
            k.code in OK_KEYS -> KeyAction.PAUSE_SCREEN_RESUME
            k.code == KeyEvent.KEYCODE_BACK || k.code in DPAD_KEYS -> KeyAction.PAUSE_SCREEN_EXIT
            else -> KeyAction.PAUSE_SCREEN_DISMISS
        }
        UiState.OVERLAY_NO_FOCUS, UiState.OVERLAY_BUTTONS, UiState.OVERLAY_BAR -> overlay(state, k)
        UiState.OVERLAY_TOUCH ->
            if (k.code == KeyEvent.KEYCODE_BACK) KeyAction.CANCEL_SEEK_HIDE_AND_PASS else hidden(k)
        UiState.NEXT_CARD -> when {
            k.code == KeyEvent.KEYCODE_BACK -> KeyAction.DISMISS_NEXT_CARD
            !k.live && k.code in OK_KEYS -> KeyAction.PLAY_NEXT_EPISODE
            else -> hidden(k)
        }
        UiState.OVERLAY_HIDDEN -> hidden(k)
    }

    /** Panneau d'erreur : la chaîne morte n'empêche pas de zapper ; le reste va aux boutons du panneau. */
    private fun errorPanel(k: KeyInput): KeyAction {
        if (!k.live && !k.catchup) return KeyAction.PASS
        return when (k.code) {
            KeyEvent.KEYCODE_CHANNEL_UP -> KeyAction.ZAP_UP
            KeyEvent.KEYCODE_CHANNEL_DOWN -> KeyAction.ZAP_DOWN
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (k.live) KeyAction.LAST_CHANNEL else KeyAction.PASS
            else -> if (k.live && digitOf(k.code) != null) KeyAction.DIGIT else KeyAction.PASS
        }
    }

    /** Commandes complètes affichées (TV). */
    private fun overlay(state: UiState, k: KeyInput): KeyAction {
        val code = k.code
        if (code == KeyEvent.KEYCODE_BACK) return KeyAction.CANCEL_SEEK_AND_HIDE
        // Touches média, chiffres, CH+ / CH-, MENU : traitement habituel, commandes laissées affichées.
        if (code !in DPAD_KEYS && code !in OK_KEYS) return hidden(k)
        return when (state) {
            UiState.OVERLAY_NO_FOCUS -> KeyAction.FOCUS_PLAY_PAUSE
            UiState.OVERLAY_BAR -> when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> KeyAction.BAR_SEEK_BACK
                KeyEvent.KEYCODE_DPAD_RIGHT -> KeyAction.BAR_SEEK_FWD
                KeyEvent.KEYCODE_DPAD_DOWN -> KeyAction.FOCUS_PLAY_PAUSE
                KeyEvent.KEYCODE_DPAD_UP -> KeyAction.CONSUME
                else -> KeyAction.BAR_OK
            }
            // Boutons : ▲ vers la barre, ▼ sans effet, ◀ ▶ et OK à la vue qui a le focus (clic, déplacement).
            else -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> KeyAction.FOCUS_BAR
                KeyEvent.KEYCODE_DPAD_DOWN -> KeyAction.CONSUME
                else -> KeyAction.PASS
            }
        }
    }

    /** Commandes masquées (et touches non spécifiques aux commandes). */
    private fun hidden(k: KeyInput): KeyAction {
        val code = k.code
        if (code == KeyEvent.KEYCODE_BACK) {
            // Replay lancé depuis le direct : Retour revient au direct.
            return when {
                k.catchup -> KeyAction.BACK_TO_LIVE
                k.overlayVisible -> KeyAction.HIDE_OVERLAY
                else -> KeyAction.PASS
            }
        }
        if (code in VOLUME_KEYS) return KeyAction.PASS
        if (k.catchup) when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> return KeyAction.BACK_TO_LIVE
            KeyEvent.KEYCODE_CHANNEL_UP -> return KeyAction.ZAP_UP
            KeyEvent.KEYCODE_CHANNEL_DOWN -> return KeyAction.ZAP_DOWN
        }
        if (code in TRACK_KEYS) return KeyAction.OPEN_TRACKS
        return if (k.live) hiddenLive(k) else hiddenVod(k)
    }

    private fun hiddenLive(k: KeyInput): KeyAction {
        val code = k.code
        val control = k.liveControl
        when (code) {
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> return KeyAction.SHOW_INFO
            KeyEvent.KEYCODE_CHANNEL_UP -> return KeyAction.ZAP_UP
            KeyEvent.KEYCODE_CHANNEL_DOWN -> return KeyAction.ZAP_DOWN
            KeyEvent.KEYCODE_LAST_CHANNEL -> return KeyAction.LAST_CHANNEL
        }
        if (digitOf(code) != null) return KeyAction.DIGIT
        // Contrôle du direct : touches média (même commandes affichées).
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> return if (control) KeyAction.LIVE_PLAY_PAUSE else KeyAction.PASS
            KeyEvent.KEYCODE_MEDIA_PLAY -> return if (control) KeyAction.LIVE_PLAY else KeyAction.PASS
            KeyEvent.KEYCODE_MEDIA_PAUSE -> return if (control) KeyAction.LIVE_PAUSE else KeyAction.PASS
            KeyEvent.KEYCODE_MEDIA_REWIND -> return if (control) KeyAction.LIVE_REWIND else KeyAction.PASS
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return if (control) KeyAction.LIVE_FAST_FORWARD else KeyAction.PASS
            KeyEvent.KEYCODE_MEDIA_NEXT -> return if (control) KeyAction.GO_LIVE else KeyAction.PASS
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return KeyAction.RESTART_PROGRAM
        }
        if (k.touch) return KeyAction.PASS
        return when (code) {
            // ▲ / ▼ : liste des chaînes (le zapping direct reste sur CH+ / CH-).
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> KeyAction.OPEN_CHANNEL_LIST
            // ◀ / ▶ : ±10 s dans le direct enregistré, barre affichée ; sans contrôle : changement de source.
            KeyEvent.KEYCODE_DPAD_LEFT -> if (control) KeyAction.LIVE_SEEK_BACK else KeyAction.SOURCE_PREV
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (control) KeyAction.LIVE_SEEK_FWD else KeyAction.SOURCE_NEXT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE -> KeyAction.LIVE_OK
            else -> KeyAction.PASS
        }
    }

    private fun hiddenVod(k: KeyInput): KeyAction {
        when (k.code) {
            KeyEvent.KEYCODE_MEDIA_REWIND -> return KeyAction.VOD_REWIND
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return KeyAction.VOD_FAST_FORWARD
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> return KeyAction.VOD_PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY -> return KeyAction.VOD_PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> return KeyAction.VOD_PAUSE
            KeyEvent.KEYCODE_MEDIA_NEXT -> return KeyAction.NEXT_EPISODE
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return KeyAction.SEEK_TO_START
        }
        if (k.touch) return KeyAction.PASS
        return when (k.code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> KeyAction.SEEK_BACK
            KeyEvent.KEYCODE_DPAD_RIGHT -> KeyAction.SEEK_FWD
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE -> KeyAction.VOD_OK
            KeyEvent.KEYCODE_DPAD_DOWN -> KeyAction.SHOW_OVERLAY_BUTTONS
            KeyEvent.KEYCODE_DPAD_UP -> KeyAction.SHOW_OVERLAY_BAR
            KeyEvent.KEYCODE_INFO -> KeyAction.SHOW_OVERLAY
            else -> KeyAction.PASS
        }
    }
}

/** Règles de lecture sans dépendance Android (testées sur la JVM). */
internal object PlaybackRules {
    /** Marge de fin : max(30 s, 3 % de la durée). */
    fun endMarginMs(durationMs: Long): Long = maxOf(30_000L, durationMs * 3 / 100)

    /**
     * Fin de lecture VOD « normale » : durée connue et position dans la marge de fin. Sinon, le flux s'est
     * arrêté trop tôt (coupure côté serveur) : à traiter comme une erreur, pas comme un film terminé.
     */
    fun endedNaturally(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs - endMarginMs(durationMs)

    /**
     * Lecture automatique après une nouvelle tentative / un changement de source : jamais derrière le panneau
     * « Reprendre ? », sinon selon l'intention en cours (pause conservée).
     */
    fun retryAutoPlay(resumePanelOpen: Boolean, playWhenReady: Boolean): Boolean = !resumePanelOpen && playWhenReady

    /**
     * Sauvegarde de la progression : seulement quand la durée de l'élément est connue (lecteur prêt) et que la
     * position a réellement changé depuis la dernière sauvegarde (sinon « updated » serait avancé pour rien).
     */
    private const val LIVE_BLOCK_MS = 30 * 60_000L

    /**
     * Échelle (heure de début, heure de fin) de la barre du direct : le programme si le guide le connaît, sinon
     * la fenêtre enregistrée arrondie au multiple de 30 min supérieur. Elle ne bouge pas quand le tampon grandit
     * (pas de sauts du curseur), sauf au passage d'un palier de 30 min sans guide.
     */
    fun liveBarRange(programStart: Long, programEnd: Long, windowStart: Long, liveWall: Long): Pair<Long, Long> {
        if (programStart > 0 && programEnd > programStart && liveWall >= programStart) return programStart to programEnd
        val len = (liveWall - windowStart).coerceAtLeast(1)
        val span = (len + LIVE_BLOCK_MS - 1) / LIVE_BLOCK_MS * LIVE_BLOCK_MS
        return windowStart to windowStart + span
    }

    fun shouldSave(durationKnown: Boolean, positionMs: Long, lastSavedPositionMs: Long?): Boolean {
        if (!durationKnown || positionMs < 5_000) return false
        return lastSavedPositionMs == null || kotlin.math.abs(positionMs - lastSavedPositionMs) >= 1_000
    }
}
