package com.fluxfr.tv.player

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Mode nuit : compression de dynamique sur la session audio du lecteur (pics atténués, dialogues
 * remontés). DynamicsProcessing (API 28+) : compresseur pleine bande + limiteur ; sinon LoudnessEnhancer.
 * Sans effet quand l'audio part en sortie directe (AC3 / E-AC3 / DTS vers une barre de son).
 */
class NightMode {
    var enabled = false
    private var effect: AudioEffect? = null
    private var session = 0
    private var channels = 0

    /** Crée / recrée l'effet pour cette session ; le libère si le mode est désactivé. */
    fun update(audioSessionId: Int, channelCount: Int) {
        val ch = channelCount.takeIf { it in 1..8 } ?: 2
        if (!enabled || audioSessionId <= 0) { release(); return }
        if (effect != null && session == audioSessionId && channels == ch) return
        release()
        session = audioSessionId
        channels = ch
        effect = runCatching {
            if (Build.VERSION.SDK_INT >= 28) dynamics(audioSessionId, ch) else loudness(audioSessionId)
        }.recoverCatching {
            Log.w(TAG, "DynamicsProcessing indisponible, repli LoudnessEnhancer", it)
            loudness(audioSessionId)
        }.onFailure { Log.w(TAG, "Mode nuit indisponible", it) }.getOrNull()
        if (effect != null) Log.i(TAG, "Mode nuit actif (session $audioSessionId, $ch canaux, ${effect!!.javaClass.simpleName})")
    }

    fun release() {
        effect?.let { e -> runCatching { e.enabled = false }; runCatching { e.release() } }
        effect = null
        session = 0
        channels = 0
    }

    @RequiresApi(28)
    private fun dynamics(sessionId: Int, ch: Int): AudioEffect {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, ch,
            false, 0, // pré-égaliseur
            true, 1, // compresseur (une bande pleine largeur)
            false, 0, // post-égaliseur
            true, // limiteur
        ).setPreferredFrameDuration(10f).build()
        // Compresseur : au-delà de -28 dB, ratio 3,5:1 ; +9 dB de gain de compensation (dialogues).
        val band = DynamicsProcessing.MbcBand(
            true, 20_000f, 8f, 250f, 3.5f, -28f, 8f, -80f, 1f, 0f, 9f,
        )
        // Limiteur : plafonne les pics (explosions, musiques) à -3 dB.
        val limiter = DynamicsProcessing.Limiter(true, true, 0, 1f, 80f, 12f, -3f, 0f)
        for (c in 0 until ch) {
            cfg.setMbcBandByChannelIndex(c, 0, band)
            cfg.setLimiterByChannelIndex(c, limiter)
        }
        return DynamicsProcessing(0, sessionId, cfg).apply { enabled = true }
    }

    private fun loudness(sessionId: Int): AudioEffect = LoudnessEnhancer(sessionId).apply {
        setTargetGain(600) // +6 dB, avec le limiteur intégré de l'effet
        enabled = true
    }

    private companion object { const val TAG = "FluxNight" }
}
