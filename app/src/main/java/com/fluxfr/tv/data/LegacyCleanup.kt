package com.fluxfr.tv.data

import android.content.Context
import java.io.File

/**
 * Ménage unique (une fois par installation) des données laissées par l'ancienne intégration Trakt, retirée
 * de l'appli : fichiers de cache, jetons et réglages. Sans effet sur le reste ; en arrière-plan.
 */
object LegacyCleanup {
    private const val PREFS = "fluxfr"
    private const val KEY_DONE = "legacyCleanupV1"
    private const val MARK = "trakt"

    fun runOnce(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        Thread {
            runCatching { clean(app) }
            prefs.edit().putBoolean(KEY_DONE, true).apply()
        }.apply { isDaemon = true }.start()
    }

    private fun clean(app: Context) {
        app.filesDir.listFiles()?.filter { it.name.contains(MARK, ignoreCase = true) }?.forEach { it.deleteRecursively() }
        val prefsDir = File(app.applicationInfo.dataDir, "shared_prefs")
        prefsDir.listFiles()?.filter { it.name.endsWith(".xml") }?.forEach { f ->
            val name = f.name.removeSuffix(".xml")
            val sp = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (name.contains(MARK, ignoreCase = true)) {
                sp.edit().clear().commit()
                f.delete()
            } else {
                val keys = sp.all.keys.filter { it.contains(MARK, ignoreCase = true) }
                if (keys.isNotEmpty()) sp.edit().apply { keys.forEach { remove(it) } }.apply()
            }
        }
    }
}
