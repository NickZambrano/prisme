package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgCacheRulesTest {
    private val now = 1_800_000_000_000L
    private val ttl = 30 * 60_000L
    private val p = EpgProgram("JT", null, now - 60_000, now + 60_000)

    @Test fun successIsCachedForFullTtl() {
        assertEquals(now, EpgCacheRules.stamp(now, ttl, ok = true))
    }

    @Test fun failureExpiresAfterThirtySeconds() {
        val at = EpgCacheRules.stamp(now, ttl, ok = false)
        // Lecture du cache : valable tant que now' - at < ttl.
        assertTrue(now + 29_000 - at < ttl)
        assertTrue(now + 30_000 - at >= ttl)
        // Durée de vie plus courte que 30 s : jamais plus longue que la durée normale.
        assertEquals(now, EpgCacheRules.stamp(now, 10_000, ok = false))
    }

    @Test fun failureKeepsLastGoodGuide() {
        assertEquals(listOf(p), EpgCacheRules.keep(listOf(p), null))
        assertEquals(emptyList<EpgProgram>(), EpgCacheRules.keep<EpgProgram>(null, null))
        // Réponse valable, même vide : elle remplace l'ancienne.
        assertEquals(emptyList<EpgProgram>(), EpgCacheRules.keep(listOf(p), emptyList()))
    }
}
