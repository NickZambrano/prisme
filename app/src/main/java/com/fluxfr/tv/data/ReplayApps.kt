package com.fluxfr.tv.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * Replays : raccourcis vers les applications officielles des chaînes (leurs vidéos sont protégées,
 * on ne les lit pas dans Prisme). Seules les applis installées sur la TV sont proposées.
 * Chaque paquet doit aussi figurer dans les <queries> du manifeste (visibilité Android 11+).
 */
object ReplayApps {
    class Known(val pkg: String, val name: String, val channels: String)

    class App(val pkg: String, val name: String, val channels: String, val banner: Bitmap?)

    val KNOWN = listOf(
        Known("fr.tf1.mytf1", "TF1+", "TF1, TMC, TFX, TF1 Séries Films, LCI"),
        Known("fr.m6.m6replay", "M6+", "M6, W9, 6ter, Gulli, Paris Première"),
        Known("fr.francetv.pluzz", "france.tv", "France 2, 3, 4, 5, franceinfo"),
        Known("tv.arte.plus7", "ARTE", "ARTE"),
        Known("com.canal.android.canal", "CANAL+", "CANAL+, C8, CNews, CStar"),
        Known("net.oqee.androidtv.store", "Oqee", "Replay des chaînes Free"),
    )

    /** Applis de replay installées, avec leur bannière TV (ou leur icône). Lecture du PackageManager : hors thread principal. */
    fun installed(ctx: Context): List<App> {
        val pm = ctx.packageManager
        return KNOWN.mapNotNull { k ->
            val info = try { pm.getApplicationInfo(k.pkg, 0) } catch (e: PackageManager.NameNotFoundException) { return@mapNotNull null }
            if (launchIntent(ctx, k.pkg) == null) return@mapNotNull null
            val drawable = runCatching { pm.getApplicationBanner(info) ?: pm.getApplicationIcon(info) }.getOrNull()
            val bmp = drawable?.let { d ->
                runCatching {
                    val w = d.intrinsicWidth.takeIf { it > 0 } ?: 320
                    val h = d.intrinsicHeight.takeIf { it > 0 } ?: 180
                    d.toBitmap(w, h)
                }.getOrNull()
            }
            App(k.pkg, k.name, k.channels, bmp)
        }
    }

    private fun launchIntent(ctx: Context, pkg: String): Intent? {
        val pm = ctx.packageManager
        return pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
    }

    /** Ouvre l'appli ; false si elle n'est plus installée. */
    fun open(ctx: Context, pkg: String): Boolean {
        val i = launchIntent(ctx, pkg) ?: return false
        return runCatching {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}
