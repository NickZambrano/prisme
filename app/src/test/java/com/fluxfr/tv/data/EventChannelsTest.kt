package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EventChannelsTest {
    private val paris = TimeZone.getTimeZone("Europe/Paris")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        Calendar.getInstance(paris).apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis

    private val now = at(2026, 11, 11, 17, 0)

    @Test fun datedFormat() {
        val e = EventChannels.parse("DAZN FR 003| Lyon vs. Nanterre | BCL (2026-11-11 18:30:00)", now)
        assertNotNull(e)
        assertEquals("Lyon vs. Nanterre", e!!.title)
        assertEquals("BCL", e.competition)
        assertEquals(at(2026, 11, 11, 18, 30), e.startMs)
    }

    @Test fun datedFormatWithoutSeconds() {
        val e = EventChannels.parse("PRIME FR 007| Rouge vs. Bleu (2026-11-12 01:00)", now)
        assertEquals(at(2026, 11, 12, 1, 0), e!!.startMs)
        assertEquals("Rouge vs. Bleu", e.title)
        assertNull(e.competition)
    }

    @Test fun atTimeFormatInfersYear() {
        val e = EventChannels.parse("Snooker: Joueur A vs Joueur B @ Nov 11 08:00 PM :MAX FR 01", now)
        assertEquals("Joueur A vs Joueur B", e!!.title)
        assertEquals("Snooker", e.competition)
        assertEquals(at(2026, 11, 11, 20, 0), e.startMs)
        // « 13:30 PM » : déjà sur 24 h.
        assertEquals(at(2026, 11, 11, 13, 30), EventChannels.parse("Golf: Open @ Nov 11 13:30 PM :MAX FR 08", now)!!.startMs)
        // Janvier vu en décembre : année suivante.
        val dec = at(2026, 12, 30, 12, 0)
        assertEquals(at(2027, 1, 2, 9, 0), EventChannels.parse("Ski: Slalom @ Jan 2 09:00 AM :MAX FR 03", dec)!!.startMs)
    }

    @Test fun roundOnlyTitleGetsCompetition() {
        val e = EventChannels.parse("DAZN FR 001| 1er tour | Tournoi d'échecs (2026-11-11 14:45:00)", now)
        assertEquals("Tournoi d'échecs · 1er tour", e!!.title)
        assertEquals("Tournoi d'échecs", e.competition)
    }

    @Test fun staleAndEmptySlots() {
        assertTrue(EventChannels.isStale("PRIME FR 024| A vs. B (2026-08-06 22:28:00)", now))
        assertFalse(EventChannels.isStale("DAZN FR 003| A vs. B (2026-11-11 18:30:00)", now))
        assertTrue(EventChannels.isEmptySlot("DAZN FR 045"))
        assertTrue(EventChannels.isEmptySlot("PRIME FR 012 |"))
        assertFalse(EventChannels.isEmptySlot("DAZN 1"))
    }

    @Test fun ordinaryChannelsAreNotEvents() {
        assertNull(EventChannels.parse("TF1", now))
        assertNull(EventChannels.parse("CANAL+ SPORT 360", now))
        assertNull(EventChannels.parse("France 2 (FR)", now))
    }

    @Test fun unlabelledSportAndEventChannelsAreKept() {
        val f = ContentFilter(ContentFilter.parseKeywords("FR").joinToString(), "")
        assertTrue(f.acceptLive(Quality.UNKNOWN, "DAZN FR 003| A vs. B | BCL (2026-11-11 18:30:00)"))
        assertTrue(f.acceptLive(Quality.UNKNOWN, "CANAL+ FORMULA 1"))
        assertFalse(f.acceptLive(Quality.UNKNOWN, "DAZN FR 045"))
        assertFalse(f.acceptLive(Quality.UNKNOWN, "STAR ACADEMY"))
        assertFalse(f.acceptLive(Quality.LOW, "CANAL+ SPORT"))
        assertTrue(f.acceptLive(Quality.HD, "TF1"))
    }
}
