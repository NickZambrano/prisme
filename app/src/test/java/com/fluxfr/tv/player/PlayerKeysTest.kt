package com.fluxfr.tv.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerKeysTest {
    private val LEFT = KeyEvent.KEYCODE_DPAD_LEFT
    private val RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
    private val UP = KeyEvent.KEYCODE_DPAD_UP
    private val DOWN = KeyEvent.KEYCODE_DPAD_DOWN
    private val OK = KeyEvent.KEYCODE_DPAD_CENTER
    private val BACK = KeyEvent.KEYCODE_BACK

    private fun vod(code: Int) = KeyInput(code, live = false)
    private fun live(code: Int, control: Boolean = true) = KeyInput(code, live = true, liveControl = control)

    // --- État ---

    @Test fun stateFocusOnButtonsWhenOverlayShownAndBarNotFocused() {
        val f = UiFlags(overlayFull = true, overlayVisible = true, overlayFocusInside = true)
        assertEquals(UiState.OVERLAY_BUTTONS, PlayerKeys.state(f))
        assertEquals(UiState.OVERLAY_BAR, PlayerKeys.state(f.copy(barFocused = true)))
        assertEquals(UiState.OVERLAY_NO_FOCUS, PlayerKeys.state(f.copy(overlayFocusInside = false)))
        assertEquals(UiState.OVERLAY_TOUCH, PlayerKeys.state(f.copy(touch = true)))
    }

    @Test fun statePanelsWinOverEverything() {
        val busy = UiFlags(channelListOpen = true, pauseScreenShown = true, overlayFull = true, nextCardVisible = true)
        assertEquals(UiState.ERROR_PANEL, PlayerKeys.state(busy.copy(panel = PanelKind.ERROR)))
        assertEquals(UiState.RESUME_PANEL, PlayerKeys.state(busy.copy(panel = PanelKind.RESUME)))
        assertEquals(UiState.TRACKS_PANEL, PlayerKeys.state(busy.copy(panel = PanelKind.TRACKS)))
        assertEquals(UiState.QUALITY_PANEL, PlayerKeys.state(busy.copy(panel = PanelKind.QUALITY)))
        assertEquals(UiState.CHANNEL_LIST, PlayerKeys.state(busy))
        assertEquals(UiState.PAUSE_SCREEN, PlayerKeys.state(busy.copy(channelListOpen = false)))
    }

    @Test fun stateNextCardOnlyWhenOverlayHidden() {
        assertEquals(UiState.NEXT_CARD, PlayerKeys.state(UiFlags(nextCardVisible = true)))
        assertEquals(UiState.OVERLAY_HIDDEN, PlayerKeys.state(UiFlags(nextCardVisible = true, overlayVisible = true)))
        assertEquals(UiState.OVERLAY_HIDDEN, PlayerKeys.state(UiFlags()))
    }

    // --- Bug 1 : boutons des commandes ---

    @Test fun overlayButtonsReceiveOkAndLeftRight() {
        for (k in listOf(vod(OK), vod(LEFT), vod(RIGHT), live(OK), live(LEFT), live(RIGHT), live(RIGHT, control = false),
            vod(KeyEvent.KEYCODE_ENTER))) {
            assertEquals("code ${k.code} live=${k.live}", KeyAction.PASS, PlayerKeys.route(UiState.OVERLAY_BUTTONS, k))
        }
        assertEquals(KeyAction.FOCUS_BAR, PlayerKeys.route(UiState.OVERLAY_BUTTONS, vod(UP)))
        assertEquals(KeyAction.CONSUME, PlayerKeys.route(UiState.OVERLAY_BUTTONS, vod(DOWN)))
        assertEquals(KeyAction.CANCEL_SEEK_AND_HIDE, PlayerKeys.route(UiState.OVERLAY_BUTTONS, vod(BACK)))
    }

    @Test fun overlayBarScrubsAndOkCommits() {
        assertEquals(KeyAction.BAR_SEEK_BACK, PlayerKeys.route(UiState.OVERLAY_BAR, vod(LEFT)))
        assertEquals(KeyAction.BAR_SEEK_FWD, PlayerKeys.route(UiState.OVERLAY_BAR, live(RIGHT)))
        assertEquals(KeyAction.BAR_OK, PlayerKeys.route(UiState.OVERLAY_BAR, vod(OK)))
        assertEquals(KeyAction.FOCUS_PLAY_PAUSE, PlayerKeys.route(UiState.OVERLAY_BAR, vod(DOWN)))
        assertEquals(KeyAction.CONSUME, PlayerKeys.route(UiState.OVERLAY_BAR, vod(UP)))
    }

    @Test fun overlayShownKeepsZapAndMediaKeys() {
        assertEquals(KeyAction.ZAP_UP, PlayerKeys.route(UiState.OVERLAY_BUTTONS, live(KeyEvent.KEYCODE_CHANNEL_UP)))
        assertEquals(KeyAction.DIGIT, PlayerKeys.route(UiState.OVERLAY_BAR, live(KeyEvent.KEYCODE_5)))
        assertEquals(KeyAction.VOD_PLAY_PAUSE, PlayerKeys.route(UiState.OVERLAY_BUTTONS, vod(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        assertEquals(KeyAction.FOCUS_PLAY_PAUSE, PlayerKeys.route(UiState.OVERLAY_NO_FOCUS, vod(RIGHT)))
    }

    // --- Bug 3 : panneau d'erreur ---

    @Test fun errorPanelStillZaps() {
        val s = UiState.ERROR_PANEL
        assertEquals(KeyAction.ZAP_UP, PlayerKeys.route(s, live(KeyEvent.KEYCODE_CHANNEL_UP)))
        assertEquals(KeyAction.ZAP_DOWN, PlayerKeys.route(s, live(KeyEvent.KEYCODE_CHANNEL_DOWN)))
        assertEquals(KeyAction.LAST_CHANNEL, PlayerKeys.route(s, live(KeyEvent.KEYCODE_LAST_CHANNEL)))
        assertEquals(KeyAction.DIGIT, PlayerKeys.route(s, live(KeyEvent.KEYCODE_7)))
        assertEquals(KeyAction.DIGIT, PlayerKeys.route(s, live(KeyEvent.KEYCODE_NUMPAD_3)))
        // Boutons du panneau : navigation standard.
        assertEquals(KeyAction.PASS, PlayerKeys.route(s, live(OK)))
        assertEquals(KeyAction.PASS, PlayerKeys.route(s, live(LEFT)))
        assertEquals(KeyAction.PASS, PlayerKeys.route(s, live(BACK)))
        // VOD : pas de zapping.
        assertEquals(KeyAction.PASS, PlayerKeys.route(s, vod(KeyEvent.KEYCODE_CHANNEL_UP)))
        // Replay lancé depuis le direct : CH+ / CH- changent de chaîne.
        assertEquals(KeyAction.ZAP_UP, PlayerKeys.route(s, KeyInput(KeyEvent.KEYCODE_CHANNEL_UP, live = false, catchup = true)))
    }

    // --- Bug 6 : panneau « Reprendre ? » ---

    @Test fun resumePanelRetryDoesNotAutoplay() {
        assertFalse(PlaybackRules.retryAutoPlay(resumePanelOpen = true, playWhenReady = true))
        assertFalse(PlaybackRules.retryAutoPlay(resumePanelOpen = false, playWhenReady = false))
        assertTrue(PlaybackRules.retryAutoPlay(resumePanelOpen = false, playWhenReady = true))
        assertEquals(KeyAction.PASS, PlayerKeys.route(UiState.RESUME_PANEL, vod(OK)))
        assertEquals(KeyAction.PASS, PlayerKeys.route(UiState.RESUME_PANEL, vod(RIGHT)))
    }

    // --- Commandes masquées ---

    @Test fun hiddenOverlayVod() {
        val s = UiState.OVERLAY_HIDDEN
        assertEquals(KeyAction.VOD_OK, PlayerKeys.route(s, vod(OK)))
        assertEquals(KeyAction.SEEK_BACK, PlayerKeys.route(s, vod(LEFT)))
        assertEquals(KeyAction.SEEK_FWD, PlayerKeys.route(s, vod(RIGHT)))
        assertEquals(KeyAction.SHOW_OVERLAY_BUTTONS, PlayerKeys.route(s, vod(DOWN)))
        assertEquals(KeyAction.SHOW_OVERLAY_BAR, PlayerKeys.route(s, vod(UP)))
        assertEquals(KeyAction.OPEN_TRACKS, PlayerKeys.route(s, vod(KeyEvent.KEYCODE_MENU)))
        assertEquals(KeyAction.PASS, PlayerKeys.route(s, vod(BACK)))
        assertEquals(KeyAction.PASS, PlayerKeys.route(s, vod(KeyEvent.KEYCODE_VOLUME_UP)))
        // Tactile : le D-pad n'est pas utilisé.
        assertEquals(KeyAction.PASS, PlayerKeys.route(s, KeyInput(LEFT, live = false, touch = true)))
    }

    @Test fun hiddenOverlayLive() {
        val s = UiState.OVERLAY_HIDDEN
        assertEquals(KeyAction.LIVE_OK, PlayerKeys.route(s, live(OK)))
        assertEquals(KeyAction.OPEN_CHANNEL_LIST, PlayerKeys.route(s, live(UP)))
        assertEquals(KeyAction.OPEN_CHANNEL_LIST, PlayerKeys.route(s, live(DOWN)))
        assertEquals(KeyAction.LIVE_SEEK_BACK, PlayerKeys.route(s, live(LEFT)))
        assertEquals(KeyAction.LIVE_SEEK_FWD, PlayerKeys.route(s, live(RIGHT)))
        assertEquals(KeyAction.SOURCE_PREV, PlayerKeys.route(s, live(LEFT, control = false)))
        assertEquals(KeyAction.ZAP_UP, PlayerKeys.route(s, live(KeyEvent.KEYCODE_CHANNEL_UP)))
        assertEquals(KeyAction.ZAP_DOWN, PlayerKeys.route(s, live(KeyEvent.KEYCODE_CHANNEL_DOWN)))
        assertEquals(KeyAction.DIGIT, PlayerKeys.route(s, live(KeyEvent.KEYCODE_1)))
        assertEquals(KeyAction.PASS, PlayerKeys.route(s, live(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, control = false)))
        assertEquals(KeyAction.LIVE_PLAY_PAUSE, PlayerKeys.route(s, live(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        assertEquals(KeyAction.BACK_TO_LIVE, PlayerKeys.route(s, KeyInput(BACK, live = false, catchup = true)))
    }

    @Test fun backIsDeterministic() {
        // Écran de pause : Retour masque tout ; commandes masquées : Retour quitte.
        assertEquals(KeyAction.PAUSE_SCREEN_EXIT, PlayerKeys.route(UiState.PAUSE_SCREEN, vod(BACK)))
        assertEquals(KeyAction.PAUSE_SCREEN_RESUME, PlayerKeys.route(UiState.PAUSE_SCREEN, vod(OK)))
        assertEquals(KeyAction.PASS, PlayerKeys.route(UiState.OVERLAY_HIDDEN, vod(BACK)))
        assertEquals(KeyAction.HIDE_OVERLAY, PlayerKeys.route(UiState.OVERLAY_HIDDEN, KeyInput(BACK, live = true, overlayVisible = true)))
        assertEquals(KeyAction.CANCEL_SEEK_HIDE_AND_PASS, PlayerKeys.route(UiState.OVERLAY_TOUCH, vod(BACK)))
        assertEquals(KeyAction.DISMISS_NEXT_CARD, PlayerKeys.route(UiState.NEXT_CARD, vod(BACK)))
        assertEquals(KeyAction.PLAY_NEXT_EPISODE, PlayerKeys.route(UiState.NEXT_CARD, vod(OK)))
    }

    @Test fun sidePanelClosesOnBackOrMenu() {
        assertEquals(KeyAction.CLOSE_SIDE_PANEL, PlayerKeys.route(UiState.TRACKS_PANEL, vod(BACK)))
        assertEquals(KeyAction.CLOSE_SIDE_PANEL, PlayerKeys.route(UiState.QUALITY_PANEL, vod(KeyEvent.KEYCODE_MENU)))
        assertEquals(KeyAction.PASS, PlayerKeys.route(UiState.TRACKS_PANEL, vod(OK)))
    }

    // --- Règles de lecture ---

    @Test fun endedNaturallyNeedsKnownDurationAndEndMargin() {
        val d = 2 * 3600_000L // marge 3 % = 216 s
        assertTrue(PlaybackRules.endedNaturally(d, d))
        assertTrue(PlaybackRules.endedNaturally(d - 200_000, d))
        assertFalse(PlaybackRules.endedNaturally(d - 300_000, d))
        assertFalse(PlaybackRules.endedNaturally(60_000, 0))
        val short = 10 * 60_000L // marge minimale 30 s
        assertTrue(PlaybackRules.endedNaturally(short - 25_000, short))
        assertFalse(PlaybackRules.endedNaturally(short - 40_000, short))
    }

    @Test fun progressNotSavedBeforeReadyNorWithoutChange() {
        assertFalse(PlaybackRules.shouldSave(durationKnown = false, positionMs = 120_000, lastSavedPositionMs = null))
        assertTrue(PlaybackRules.shouldSave(durationKnown = true, positionMs = 120_000, lastSavedPositionMs = null))
        assertFalse(PlaybackRules.shouldSave(durationKnown = true, positionMs = 120_400, lastSavedPositionMs = 120_000))
        assertTrue(PlaybackRules.shouldSave(durationKnown = true, positionMs = 130_000, lastSavedPositionMs = 120_000))
        assertFalse(PlaybackRules.shouldSave(durationKnown = true, positionMs = 3_000, lastSavedPositionMs = null))
    }

    @Test fun liveBarRangeIsStable() {
        val h = 3600_000L
        // Guide connu : le programme, quelle que soit la taille du tampon.
        assertEquals(10 * h to 11 * h, PlaybackRules.liveBarRange(10 * h, 11 * h, 10 * h + 60_000, 10 * h + 120_000))
        assertEquals(10 * h to 11 * h, PlaybackRules.liveBarRange(10 * h, 11 * h, 10 * h + 60_000, 10 * h + 1_800_000))
        // Sans guide : fenêtre arrondie à 30 min (pas de saut quand elle grandit).
        val w = 5 * h
        assertEquals(w to w + 1_800_000, PlaybackRules.liveBarRange(0, 0, w, w + 30_000))
        assertEquals(w to w + 1_800_000, PlaybackRules.liveBarRange(0, 0, w, w + 1_200_000))
        assertEquals(w to w + 3_600_000, PlaybackRules.liveBarRange(0, 0, w, w + 1_900_000))
    }

    @Test fun digits() {
        assertEquals(0, PlayerKeys.digitOf(KeyEvent.KEYCODE_0))
        assertEquals(9, PlayerKeys.digitOf(KeyEvent.KEYCODE_NUMPAD_9))
        assertEquals(null, PlayerKeys.digitOf(OK))
    }

    @Test fun accountFingerprintChangesWithServerOrUser() {
        val a = AccountLimits.fingerprint("xtream", "http://a.example:8080/", "bob")
        assertEquals(a, AccountLimits.fingerprint("xtream", "HTTP://A.EXAMPLE:8080", "bob"))
        assertTrue(a != AccountLimits.fingerprint("xtream", "http://a.example:8080", "alice"))
        assertTrue(a != AccountLimits.fingerprint("xtream", "http://b.example:8080", "bob"))
        assertEquals(null, AccountLimits.fingerprint("m3u", "http://a.example", "bob"))
        assertTrue(ProviderConnections.isSingle(-1))
        assertTrue(ProviderConnections.isSingle(1))
        assertFalse(ProviderConnections.allowsExtra(1))
        assertTrue(ProviderConnections.allowsExtra(2))
    }
}
