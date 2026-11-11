package com.fluxfr.tv.tvhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * Messages de l'écran d'accueil TV :
 * - ACTION_INITIALIZE_PROGRAMS (installation, données effacées) → republie la rangée « Continuer à regarder » ;
 * - ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED (programme retiré par l'utilisateur) → on l'oublie.
 */
class WatchNextReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            when (intent.action) {
                TvContractCompat.ACTION_INITIALIZE_PROGRAMS -> {
                    val result = goAsync()
                    WatchNextSync.syncThen(context) { runCatching { result.finish() } }
                }
                TvContractCompat.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED -> {
                    val id = intent.getLongExtra(TvContractCompat.EXTRA_WATCH_NEXT_PROGRAM_ID, -1L)
                    WatchNextSync.onProgramRemoved(context, id)
                }
            }
        }
    }
}
