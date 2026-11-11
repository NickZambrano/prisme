package com.fluxfr.tv.player

/** État du contrôle du direct affiché par la barre des commandes (heures en ms). */
internal class TimeshiftState(
    val windowStartWall: Long,
    val liveWall: Long,
    /** Position lue (ou visée pendant un saut). */
    val playWall: Long,
    val paused: Boolean,
    val programTitle: String?,
    val programStart: Long,
    val programEnd: Long,
    /** Saut en préparation (ms, négatif = en arrière) ; null sinon. */
    val pendingDeltaMs: Long?,
) {
    val behindMs: Long get() = (liveWall - playWall).coerceAtLeast(0)
    val atLive: Boolean get() = behindMs < LIVE_EDGE_MS

    companion object {
        const val LIVE_EDGE_MS = 5_000L
    }
}
