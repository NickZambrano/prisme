package com.fluxfr.tv.player

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.media3.common.Format
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fréquence d'image automatique : choisit un mode d'affichage de même résolution dont la fréquence
 * est un multiple entier de celle de la vidéo (23,976 → 23,976 ; 25 → 50 ; 29,97 → 59,94…).
 * Passe par window.attributes.preferredDisplayModeId (API 23+).
 */
class AutoFrameRate(private val activity: Activity) {
    private var lastFps = 0f
    private var applied = false

    /** Appelé à chaque nouveau format vidéo ; ne fait rien si la fréquence n'a pas changé. */
    fun onVideoFormat(format: Format?) {
        if (Build.VERSION.SDK_INT < 23) return
        val fps = format?.frameRate ?: return
        if (fps <= 1f || fps.isNaN()) return
        if (abs(fps - lastFps) < 0.001f) return
        lastFps = fps
        runCatching { apply(fps) }.onFailure { Log.w(TAG, "Changement de mode impossible", it) }
    }

    /** Rend la main au système (mode d'origine). */
    fun restore() {
        lastFps = 0f
        if (!applied || Build.VERSION.SDK_INT < 23) return
        applied = false
        runCatching {
            val lp = activity.window.attributes
            lp.preferredDisplayModeId = 0
            activity.window.attributes = lp
            Log.i(TAG, "Mode d'affichage d'origine restauré")
        }
    }

    @Suppress("DEPRECATION")
    private fun display(): Display? =
        if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay

    @RequiresApi(23)
    private fun apply(rawFps: Float) {
        val display = display() ?: return
        val cur = display.mode
        val fps = canonical(rawFps)
        val matches = display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .filter { m ->
                val k = (m.refreshRate / fps).roundToInt()
                k >= 1 && abs(m.refreshRate - k * fps) < 0.01f * k
            }
        if (matches.isEmpty()) {
            Log.i(TAG, "Vidéo %.3f i/s : aucun mode compatible (actuel %.3f Hz)".format(rawFps, cur.refreshRate))
            return
        }
        // 23,976 / 24 i/s : fréquence identique en priorité ; 25 / 29,97 / 30 : doublée (50 / 59,94 / 60).
        val best = if (fps < 24.5f) matches.minBy { it.refreshRate }
        else matches.sortedWith(compareBy<Display.Mode>({ if (it.refreshRate >= 47f) 0 else 1 }, { it.refreshRate })).first()
        val lp = activity.window.attributes
        if (best.modeId == cur.modeId && (lp.preferredDisplayModeId == 0 || lp.preferredDisplayModeId == best.modeId)) {
            Log.i(TAG, "Vidéo %.3f i/s : mode actuel %.3f Hz déjà adapté".format(rawFps, cur.refreshRate))
            return
        }
        if (lp.preferredDisplayModeId == best.modeId) return
        lp.preferredDisplayModeId = best.modeId
        activity.window.attributes = lp
        applied = true
        Log.i(
            TAG, "Vidéo %.3f i/s : %dx%d %.3f Hz → %.3f Hz (mode %d)".format(
                rawFps, cur.physicalWidth, cur.physicalHeight, cur.refreshRate, best.refreshRate, best.modeId,
            )
        )
    }

    /** Ramène une fréquence arrondie (23,98…) à sa valeur exacte (24000/1001…). */
    private fun canonical(fps: Float): Float =
        STANDARD.minBy { abs(it - fps) }.takeIf { abs(it - fps) < 0.012f } ?: fps

    private companion object {
        const val TAG = "FluxAfr"
        val STANDARD = floatArrayOf(
            24000f / 1001f, 24f, 25f, 30000f / 1001f, 30f, 48000f / 1001f, 48f, 50f, 60000f / 1001f, 60f,
        ).toList()
    }
}
