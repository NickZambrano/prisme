package com.fluxfr.tv.player

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Passage obligé de toutes les connexions au fournisseur ouvertes par le lecteur : enregistreur du direct
 * ([TimeshiftRecorder]), lecteur principal (flux HTTP via [TimeshiftRoutingDataSource]), aperçu de chaîne
 * ([ChannelPreview]) et images d'aperçu VOD ([SeekThumbnails]).
 *
 * Abonnement à une seule connexion (ou nombre inconnu) : une nouvelle connexion attend que la précédente
 * soit fermée (au plus [MAX_WAIT_MS]) ; sinon le serveur refuserait la seconde ou couperait la première.
 * Aucune attente quand rien n'est ouvert (zapping rapide).
 */
object ProviderConnections {
    const val MAX_WAIT_MS = 3_000L

    /** Connexions simultanées autorisées par l'abonnement (-1 : inconnu). */
    @Volatile var maxConnections: Int = -1

    /** Une seule connexion à la fois (nombre inconnu compris). */
    val singleConnection: Boolean get() = isSingle(maxConnections)

    fun isSingle(max: Int): Boolean = max <= 1

    /** Une seconde connexion (aperçu de chaîne, images d'aperçu VOD) est permise. */
    fun allowsExtra(max: Int = maxConnections): Boolean = max >= 2

    /** Flux HTTP du fournisseur actuellement ouverts par le lecteur principal. */
    private val playerOpen = AtomicInteger(0)
    private val lock = Object()

    val playerConnections: Int get() = playerOpen.get()

    internal fun playerOpened() { playerOpen.incrementAndGet() }

    internal fun playerClosed() {
        if (playerOpen.decrementAndGet() < 0) playerOpen.set(0)
        synchronized(lock) { lock.notifyAll() }
    }

    /**
     * Avant d'ouvrir un flux du fournisseur dans le lecteur principal (fil de chargement d'ExoPlayer, jamais
     * le fil principal) : un enregistreur du direct arrêté mais pas encore déconnecté est attendu.
     */
    fun beforePlayerOpen() {
        if (!singleConnection) return
        val r = Timeshift.lastRecorderOrNull() ?: return
        if (r.stopped && r.isAlive) r.join(MAX_WAIT_MS)
    }

    /**
     * Avant que l'enregistreur du direct se connecte (fil de l'enregistreur) : les flux du fournisseur encore
     * ouverts par le lecteur principal (replay, chaîne HLS) doivent être fermés.
     */
    fun beforeRecorderOpen(isStopped: () -> Boolean) {
        if (!singleConnection || playerOpen.get() <= 0) return
        val deadline = SystemClock.elapsedRealtime() + MAX_WAIT_MS
        synchronized(lock) {
            while (playerOpen.get() > 0 && !isStopped()) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) return
                try {
                    lock.wait(minOf(left, 100))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }
}
