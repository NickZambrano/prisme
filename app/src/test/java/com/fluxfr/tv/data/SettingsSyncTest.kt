package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SettingsSyncTest {
    private fun snap(vararg d: Pair<String, SettingsSync.Domain>) = SettingsSync.Snapshot("Salon", mapOf(*d))

    @Test
    fun newestDomainWins() {
        val local = mapOf(SettingsSync.PROFILES to 100L, SettingsSync.HIDDEN to 500L, SettingsSync.FAVORITES to 0L)
        val remote = snap(
            SettingsSync.PROFILES to SettingsSync.Domain(200L, listOf<Any>()),
            SettingsSync.HIDDEN to SettingsSync.Domain(400L, listOf("live|X")),
            SettingsSync.FAVORITES to SettingsSync.Domain(1L, mapOf("main" to listOf("live:1"))),
        )
        val newer = SettingsSync.newerDomains(local, remote)
        assertEquals(setOf(SettingsSync.PROFILES, SettingsSync.FAVORITES), newer.keys)
    }

    @Test
    fun equalTimesAndUnknownDomainsAreIgnored() {
        val local = mapOf(SettingsSync.PROFILES to 100L)
        val remote = snap(
            SettingsSync.PROFILES to SettingsSync.Domain(100L, listOf<Any>()),
            "account_tokens" to SettingsSync.Domain(999L, "secret"),
            SettingsSync.PREFS_DOMAIN to SettingsSync.Domain(0L, mapOf<String, Any>()),
        )
        assertTrue(SettingsSync.newerDomains(local, remote).isEmpty())
    }

    @Test
    fun staleRemoteDetected() {
        val local = mapOf(SettingsSync.HIDDEN to 50L)
        assertTrue(SettingsSync.remoteIsStale(local, snap()))
        assertFalse(SettingsSync.remoteIsStale(local, snap(SettingsSync.HIDDEN to SettingsSync.Domain(50L, emptyList<String>()))))
        assertFalse(SettingsSync.remoteIsStale(emptyMap(), snap()))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val profiles = listOf(Profile("main", "Principal", 0, false), Profile("kids", "Enfants", 1, true, PinHash.hash("1234")))
        val s = SettingsSync.Snapshot(
            "Chambre",
            mapOf(
                SettingsSync.PROFILES to SettingsSync.Domain(1_731_000_000_123L, Profiles.toMaps(profiles)),
                SettingsSync.HIDDEN to SettingsSync.Domain(5L, listOf("live|FR: SPORT", "movie|Ados")),
                SettingsSync.FAVORITES to SettingsSync.Domain(6L, mapOf("main" to listOf("live:12", "live:7"))),
                SettingsSync.PREFS_DOMAIN to SettingsSync.Domain(7L, mapOf("uhdLive" to true, "fr" to "FR, VF")),
            ),
        )
        val back = SettingsSync.decode(SettingsSync.encode(s))
        assertNotNull(back)
        back!!
        assertEquals("Chambre", back.device)
        assertEquals(1_731_000_000_123L, back.domains[SettingsSync.PROFILES]!!.updatedAt)
        assertEquals(profiles, Profiles.fromMaps(back.domains[SettingsSync.PROFILES]!!.data as List<*>))
        assertEquals(listOf("live|FR: SPORT", "movie|Ados"), back.domains[SettingsSync.HIDDEN]!!.data)
        assertEquals(mapOf("main" to listOf("live:12", "live:7")), back.domains[SettingsSync.FAVORITES]!!.data)
        assertEquals(true, (back.domains[SettingsSync.PREFS_DOMAIN]!!.data as Map<*, *>)["uhdLive"])
    }

    @Test
    fun decodeRejectsGarbage() {
        assertNull(SettingsSync.decode("not json"))
        assertNull(SettingsSync.decode("{\"device\":\"x\"}"))
    }

    @Test
    fun liveFavoritesMergeKeepsVodAndAddsNewChannelsFirst() {
        val local = listOf("movie:1", "live:a", "series:2", "live:b")
        val merged = SettingsSync.mergeLiveFavorites(local, listOf("live:c", "live:b", "movie:9"))
        assertEquals(listOf("live:c", "movie:1", "series:2", "live:b"), merged)
    }

    @Test
    fun liveFavoritesMergeWithEmptyRemoteRemovesChannelsOnly() {
        assertEquals(listOf("movie:1"), SettingsSync.mergeLiveFavorites(listOf("live:a", "movie:1"), emptyList()))
    }

    @Test
    fun profilesFromMapsDropsInvalidAndDuplicates() {
        val list = Profiles.fromMaps(
            listOf(
                mapOf("id" to "main", "name" to "A", "color" to 2.0, "kids" to false),
                mapOf("name" to "sans id"),
                mapOf("id" to "main", "name" to "doublon"),
                "pas un objet",
            )
        )
        assertEquals(listOf(Profile("main", "A", 2, false)), list)
    }

    @Test
    fun base64MatchesJdk() {
        val rnd = Random(42)
        for (n in 0..40) {
            val b = ByteArray(n) { rnd.nextInt().toByte() }
            val expected = java.util.Base64.getEncoder().encodeToString(b)
            assertEquals(expected, Base64Codec.encode(b))
            assertTrue(b.contentEquals(Base64Codec.decode(expected)))
        }
    }

    @Test
    fun describeStatus() {
        val now = 10_000_000L
        assertEquals("Synchronisé avec Salon il y a 2 min", SettingsSync.describe(SettingsSync.Status(now - 125_000, "Salon"), now))
        assertEquals("Synchronisé avec Chambre à l'instant", SettingsSync.describe(SettingsSync.Status(now - 5_000, "Chambre"), now))
        assertEquals("il y a 3 h", SettingsSync.ago(now - 3 * 3_600_000L, now))
    }
}
