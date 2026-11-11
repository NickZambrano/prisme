package com.fluxfr.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * Contrôle de chargement du lecteur : réglages par défaut pour les films / séries, démarrage plus
 * précoce pour le direct (zapping rapide : 0,7 s de tampon au démarrage, 1,5 s après une coupure).
 */
@OptIn(UnstableApi::class)
class ZapLoadControl : DefaultLoadControl() {
    /** Élément en cours : direct ou non (lu depuis le thread de lecture). */
    @Volatile var live = false

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!live) return super.shouldStartPlayback(parameters)
        val bufferedUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        val minUs = if (parameters.rebuffering) LIVE_AFTER_REBUFFER_US else LIVE_START_US
        return bufferedUs >= minUs || super.shouldStartPlayback(parameters)
    }

    private companion object {
        const val LIVE_START_US = 700_000L
        const val LIVE_AFTER_REBUFFER_US = 1_500_000L
    }
}
