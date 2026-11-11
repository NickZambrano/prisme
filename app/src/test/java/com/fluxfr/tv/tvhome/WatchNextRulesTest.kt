package com.fluxfr.tv.tvhome

import com.fluxfr.tv.data.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchNextRulesTest {
    private fun ep(id: String, s: Int, n: Int) = Episode(id, s, n, "E$n", "http://h/series/u/p/$id.mkv", null, null, 0L)

    private val eps = listOf(ep("12", 2, 1), ep("10", 1, 2), ep("9", 1, 1), ep("13", 2, 2))

    @Test fun nextById() {
        assertEquals("10", WatchNextRules.next(eps, "9", 1, 1)?.id)
        assertEquals("12", WatchNextRules.next(eps, "10", 1, 2)?.id) // passage à la saison suivante
        assertNull(WatchNextRules.next(eps, "13", 2, 2)) // dernier épisode
    }

    @Test fun nextBySeasonNumberWhenIdUnknown() {
        assertEquals("12", WatchNextRules.next(eps, "999", 1, 2)?.id)
        assertNull(WatchNextRules.next(eps, "999", 0, 0)) // numéro inconnu : pas de supposition
    }

    @Test fun dismissalIsPerProfile() {
        assertNotEquals(WatchNextRules.dismissKey("main", "series:1"), WatchNextRules.dismissKey("kids", "series:1"))
        assertEquals("series:1", WatchNextRules.legacyDismissSlot("d:series:1"))
        assertNull(WatchNextRules.legacyDismissSlot(WatchNextRules.dismissKey("main", "series:1")))
        assertNull(WatchNextRules.legacyDismissSlot("m:series:1"))
    }

    @Test fun findEpisode() {
        assertEquals(2, WatchNextRules.find(eps, "13")?.number)
        assertNull(WatchNextRules.find(eps, "x"))
    }
}
