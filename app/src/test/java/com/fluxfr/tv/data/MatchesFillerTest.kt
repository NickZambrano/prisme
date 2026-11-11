package com.fluxfr.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchesFillerTest {
    private val h = 3600_000L
    private val t0 = 1_700_000_000_000L

    @Test fun longBlocksAreFiller() {
        assertTrue(MatchDetector.isFiller("Ligue 1 : PSG - OM", t0 + 6 * h, t0 + 12 * h))
        assertFalse(MatchDetector.isFiller("Ligue 1 : PSG - OM", t0, t0 + 2 * h))
    }

    @Test fun genericTitlesAreFiller() {
        assertTrue(MatchDetector.isFiller("Programmes", t0, t0 + h))
        assertTrue(MatchDetector.isFiller("Rediffusion", t0, t0 + h))
        assertTrue(MatchDetector.isFiller("Rediffusion : PSG - OM", t0, t0 + h))
        assertTrue(MatchDetector.isFiller("Fin des programmes", t0, t0 + h))
        assertTrue(MatchDetector.isFiller("beIN SPORTS 1", t0, t0 + h, "beIN SPORTS 1 HD"))
        assertFalse(MatchDetector.isFiller("Football : Lens - Lille", t0, t0 + h, "beIN SPORTS 1 HD"))
    }
}
