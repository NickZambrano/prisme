package com.fluxfr.tv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class CatalogFetchRulesTest {
    @Test fun throttleDetection() {
        assertTrue(CatalogFetchRules.isThrottle(IOException("Le serveur a répondu HTTP 403")))
        assertTrue(CatalogFetchRules.isThrottle(IOException("Le serveur a répondu HTTP 429")))
        assertTrue(CatalogFetchRules.isThrottle(RuntimeException("wrap", IOException("Le serveur a répondu HTTP 429"))))
        assertFalse(CatalogFetchRules.isThrottle(IOException("Le serveur a répondu HTTP 404")))
        assertFalse(CatalogFetchRules.isThrottle(IOException("Le serveur a répondu HTTP 4030")))
        assertFalse(CatalogFetchRules.isThrottle(java.net.SocketTimeoutException("timeout")))
        assertFalse(CatalogFetchRules.isThrottle(null))
    }

    @Test fun incompleteCatalogIsRefreshedEvenWhenRecent() {
        val now = 1_000_000_000L
        val max = 12 * 3600_000L
        assertFalse(CatalogFetchRules.needsRefresh(now - 1000, now, max, incomplete = false, force = false))
        assertTrue(CatalogFetchRules.needsRefresh(now - 1000, now, max, incomplete = true, force = false))
        assertTrue(CatalogFetchRules.needsRefresh(now - max, now, max, incomplete = false, force = false))
        assertTrue(CatalogFetchRules.needsRefresh(now, now, max, incomplete = false, force = true))
        // Catalogue incomplet sans cache précédent : noté avec la date 0, donc rafraîchi.
        assertTrue(CatalogFetchRules.needsRefresh(0L, now, max, incomplete = false, force = false))
    }

    @Test fun retryBackoffIsSmall() {
        assertEquals(1_000L, CatalogFetchRules.retryDelayMs(1))
        assertEquals(3_000L, CatalogFetchRules.retryDelayMs(2))
    }

    @Test fun accountKeyIgnoresSchemeAndPasswordButNotUser() {
        val a = CatalogFetchRules.accountKey(false, "", "https://srv.example:8080/", "bob")
        val b = CatalogFetchRules.accountKey(false, "", "http://SRV.example:8080", "bob")
        val c = CatalogFetchRules.accountKey(false, "", "http://srv.example:8080", "alice")
        val m = CatalogFetchRules.accountKey(true, "http://list/m3u?u=bob", "http://srv.example:8080", "bob")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, m)
        assertFalse(a.contains("bob"))
    }

    @Test fun gateLimitsConcurrencyThenDropsToOne() = runBlocking {
        val gate = AdaptiveGate(4)
        val active = AtomicInteger()
        val peakBefore = AtomicInteger()
        val peakAfter = AtomicInteger()
        val reduced = CompletableDeferred<Unit>()
        withTimeout(10_000) {
            // 8 premières tâches : au plus 4 à la fois.
            (1..8).map {
                async(Dispatchers.Default) {
                    gate.withPermit {
                        val n = active.incrementAndGet()
                        peakBefore.accumulateAndGet(n) { x, y -> maxOf(x, y) }
                        delay(20)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
            assertEquals(4, peakBefore.get())
            gate.reduceTo(1)
            reduced.complete(Unit)
            (1..6).map {
                async(Dispatchers.Default) {
                    gate.withPermit {
                        val n = active.incrementAndGet()
                        peakAfter.accumulateAndGet(n) { x, y -> maxOf(x, y) }
                        delay(10)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, peakAfter.get())
        assertEquals(1, gate.currentLimit)
        assertEquals(0, gate.activeCount)
    }

    @Test fun gateReductionWhileBusyLetsRunningFinish() = runBlocking {
        val gate = AdaptiveGate(4)
        val active = AtomicInteger()
        val peakLate = AtomicInteger()
        withTimeout(10_000) {
            val first = (1..4).map {
                async(Dispatchers.Default) { gate.withPermit { active.incrementAndGet(); delay(50); active.decrementAndGet() } }
            }
            delay(10)
            gate.reduceTo(1) // 403 reçu pendant que 4 requêtes tournent
            val late = (1..4).map {
                async(Dispatchers.Default) {
                    gate.withPermit {
                        val n = active.incrementAndGet()
                        peakLate.accumulateAndGet(n) { x, y -> maxOf(x, y) }
                        delay(5)
                        active.decrementAndGet()
                    }
                }
            }
            (first + late).awaitAll()
        }
        assertEquals(1, peakLate.get())
        assertEquals(0, gate.activeCount)
    }

    @Test fun gateNeverRaisesLimit() {
        val gate = AdaptiveGate(4)
        gate.reduceTo(6)
        assertEquals(4, gate.currentLimit)
        gate.reduceTo(0)
        assertEquals(4, gate.currentLimit)
    }
}
