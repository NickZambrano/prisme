package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Marqueurs de suppression par série / profil entier, drapeau « à envoyer », un compte Google par profil. */
class DriveMarkersTest {
    private val now = 1_800_000_000_000L

    private fun ep(id: String, series: String, updated: Long, n: Int = 1) = Progress(
        key = "ep:$id", type = Progress.EPISODE, itemId = id, title = "Série $series", subtitle = null, poster = null,
        url = null, position = 60_000, duration = 1_200_000, updated = updated, seriesId = series, season = 1, episode = n,
    )

    private fun movie(id: String, updated: Long) = Progress(
        key = "movie:$id", type = Progress.MOVIE, itemId = id, title = "Film $id", subtitle = null, poster = null,
        url = null, position = 60_000, duration = 6_000_000, updated = updated, seriesId = null, season = 0, episode = 0,
    )

    private fun doc(vararg rows: Progress, del: Map<String, Long> = emptyMap(), rev: Map<String, Long> = emptyMap()) =
        DriveDoc(rows.associateBy { it.key }, del, progressRevived = rev)

    @Test fun seriesMarkerRemovesEpisodesUnknownHere() {
        // TV A retire la série S : elle ne connaissait que l'épisode 1.
        val local = doc(del = mapOf("ep:1" to now - 100, DriveMerge.seriesMarker("S") to now - 100))
        // Le Drive (TV B) a aussi l'épisode 2 de S, vu avant le retrait, et une autre série.
        val remote = doc(ep("1", "S", now - 5000), ep("2", "S", now - 4000, 2), ep("9", "T", now - 4000))
        val m = DriveMerge.merge(local, remote, now)
        assertNull(m.progress["ep:1"])
        assertNull(m.progress["ep:2"])
        assertTrue("ep:9" in m.progress)
        assertEquals(now - 100, m.progressDeleted[DriveMerge.seriesMarker("S")]) // le marqueur circule
        assertFalse("ep:2" in m.progressDeleted) // pas de suppression par clé ajoutée inutilement

        // Sur la TV B : la fusion supprime localement les deux épisodes.
        val onB = DriveMerge.merge(remote, m, now)
        assertEquals(setOf("ep:1", "ep:2"), DriveMerge.localChanges(remote, onB, emptyList()).delete)
    }

    @Test fun laterActivityRevivesSeries() {
        val local = doc(del = mapOf(DriveMerge.seriesMarker("S") to now - 1000))
        val remote = doc(ep("3", "S", now - 10, 3))
        val m = DriveMerge.merge(local, remote, now)
        assertTrue("ep:3" in m.progress)
        assertTrue(DriveMerge.localChanges(remote, m, emptyList()).delete.isEmpty())
    }

    @Test fun allMarkerClearsEverythingOlder() {
        val local = doc(movie("new", now - 10), del = mapOf(DriveMerge.ALL_MARKER to now - 100))
        val remote = doc(movie("a", now - 5000), ep("1", "S", now - 200), movie("new", now - 20))
        val m = DriveMerge.merge(local, remote, now)
        assertEquals(setOf("movie:new"), m.progress.keys)
        assertEquals(now - 100, m.progressDeleted[DriveMerge.ALL_MARKER])
        assertEquals(setOf("movie:a", "ep:1"), DriveMerge.localChanges(remote, DriveMerge.merge(remote, m, now), emptyList()).delete)
    }

    @Test fun revivedImportBeatsMarker() {
        // Réimport Netflix après « vider l'historique » : dates anciennes, mais recréation datée après.
        val local = doc(movie("nf", now - 90L * 24 * 3600_000), rev = mapOf("movie:nf" to now - 10))
        val remote = doc(del = mapOf(DriveMerge.ALL_MARKER to now - 100))
        val m = DriveMerge.merge(local, remote, now)
        assertTrue("movie:nf" in m.progress)
    }

    @Test fun markersSurviveRoundTripAndIgnoredAsRows() {
        val d = doc(ep("1", "S", now - 10), del = mapOf(DriveMerge.seriesMarker("S") to now - 100, DriveMerge.ALL_MARKER to now - 50))
        val back = DriveCodec.decode(DriveCodec.encode(d))
        assertEquals(d.progressDeleted, back.progressDeleted)
        assertTrue(DriveMerge.isMarker("series:12"))
        assertTrue(DriveMerge.isMarker("all"))
        assertFalse(DriveMerge.isMarker("ep:12"))
        assertFalse(DriveMerge.isMarker("movie:12"))
    }

    @Test fun markerCoverage() {
        val del = mapOf(DriveMerge.seriesMarker("S") to now - 100)
        assertTrue(DriveMerge.coveredByMarker(ep("1", "S", now - 100), del)) // égalité : supprimée
        assertFalse(DriveMerge.coveredByMarker(ep("1", "S", now - 99), del))
        assertFalse(DriveMerge.coveredByMarker(ep("1", "T", now - 1000), del))
        assertFalse(DriveMerge.coveredByMarker(movie("S", now - 1000), del)) // un film n'est pas une série
        assertTrue(DriveMerge.coveredByMarker(movie("x", now - 1000), mapOf(DriveMerge.ALL_MARKER to now)))
    }

    // ------------------------------------------------------------------ drapeau « à envoyer »

    @Test fun dirtyClearedOnlyWithoutConcurrentChange() {
        assertTrue(DriveSyncRules.canClearDirty(5, 5))
        assertFalse(DriveSyncRules.canClearDirty(5, 6))
    }

    // ------------------------------------------------------------------ un compte Google par profil

    private fun link(id: String, email: String?, at: Long, order: Int) = DriveSyncRules.Link(id, "Profil $id", email, at, order)

    @Test fun laterLinkedProfileIsBlocked() {
        val a = link("a", "Jean@Gmail.com", 0, 0)
        val b = link("b", " jean@gmail.com ", now, 1)
        val c = link("c", "other@gmail.com", 0, 2)
        val all = listOf(a, b, c)
        assertEquals("a", DriveSyncRules.earlierSameAccount(b, all)?.profile)
        assertNull(DriveSyncRules.earlierSameAccount(a, all))
        assertNull(DriveSyncRules.earlierSameAccount(c, all))
    }

    @Test fun legacyTieBrokenByProfileOrder() {
        val a = link("a", "x@gmail.com", 0, 0)
        val b = link("b", "x@gmail.com", 0, 1)
        assertNull(DriveSyncRules.earlierSameAccount(a, listOf(a, b)))
        assertEquals("a", DriveSyncRules.earlierSameAccount(b, listOf(a, b))?.profile)
    }

    @Test fun blankEmailNeverMatches() {
        val a = link("a", "", 0, 0)
        val b = link("b", "", now, 1)
        assertNull(DriveSyncRules.earlierSameAccount(b, listOf(a, b)))
        assertNull(DriveSyncRules.sameAccount("", listOf(a, b)))
        assertNull(DriveSyncRules.sameAccount(null, listOf(a, b)))
    }

    @Test fun sameAccountLookup() {
        val links = listOf(link("a", "x@gmail.com", 0, 0), link("b", "y@gmail.com", 0, 1))
        assertEquals("Profil b", DriveSyncRules.sameAccount("Y@GMAIL.com", links)?.name)
        assertNull(DriveSyncRules.sameAccount("z@gmail.com", links))
    }
}
