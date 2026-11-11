package com.fluxfr.tv.player

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.fluxfr.tv.data.Http
import com.fluxfr.tv.data.Json
import com.fluxfr.tv.data.Settings
import com.fluxfr.tv.data.XtreamClient
import java.io.BufferedInputStream
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.TimeZone
import java.util.concurrent.Executor

/**
 * Informations du compte lues dans la réponse d'authentification de player_api.php, mises en cache dans
 * les préférences "fluxfr" :
 *  - connexions simultanées autorisées (user_info.max_connections, clé "maxConnections", -1 = inconnu) ;
 *  - fuseau horaire du serveur (server_info.timezone, clé "serverTimezone") : heures des URL de replay.
 */
object AccountLimits {
    private const val PREFS = "fluxfr"
    private const val KEY = "maxConnections"
    private const val KEY_AT = "maxConnectionsAt"
    private const val KEY_TZ = "serverTimezone"
    /** Empreinte (serveur + identifiant) du compte pour lequel les valeurs ci-dessus ont été lues. */
    private const val KEY_FP = "accountLimitsFor"
    private const val MAX_AGE_MS = 12 * 3600_000L
    private const val TAG = "FluxAccount"

    /** Clés mises en cache (effacées à la déconnexion et au changement de compte). */
    val CACHE_KEYS = listOf(KEY, KEY_AT, KEY_TZ, KEY_FP)

    private class Info(val maxConnections: Int?, val timezone: String?)

    /**
     * Empreinte du compte (aucun identifiant en clair dans les préférences) ; null si le compte n'est pas un
     * compte Xtream complet.
     */
    fun fingerprint(mode: String, server: String, user: String): String? {
        if (mode != Settings.MODE_XTREAM || server.isBlank() || user.isBlank()) return null
        val s = server.trim().trimEnd('/').lowercase() + "\n" + user.trim()
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return md.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun currentFingerprint(ctx: Context): String? {
        val s = Settings(ctx)
        return fingerprint(s.mode, s.server, s.user)
    }

    /** Valeurs en cache valables pour le compte actuel (sinon : inconnues). */
    private fun cacheValid(ctx: Context): Boolean {
        val fp = currentFingerprint(ctx) ?: return false
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FP, null) == fp
    }

    /** Efface les valeurs en cache (déconnexion, changement de compte). */
    fun clear(ctx: Context) {
        val e = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        CACHE_KEYS.forEach { e.remove(it) }
        e.apply()
    }

    /** Connexions simultanées autorisées ; -1 si inconnu (ou lu pour un autre compte). */
    fun cached(ctx: Context): Int {
        if (!cacheValid(ctx)) return -1
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, -1)
    }

    /** Fuseau du serveur Xtream (à défaut, ou lu pour un autre compte : celui de l'appareil). */
    fun serverTimeZone(ctx: Context): TimeZone {
        if (!cacheValid(ctx)) return TimeZone.getDefault()
        val id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TZ, null)
        return parseTimeZone(id) ?: TimeZone.getDefault()
    }

    /** « Europe/Paris », « UTC », « GMT+01:00 »… ; null si inconnu (TimeZone renvoie GMT pour un nom invalide). */
    fun parseTimeZone(id: String?): TimeZone? {
        val s = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val tz = TimeZone.getTimeZone(s)
        if (tz.id == "GMT" && !s.equals("GMT", ignoreCase = true) && !s.equals("UTC", ignoreCase = true) &&
            !s.startsWith("GMT", ignoreCase = true) && !s.startsWith("Etc/", ignoreCase = true)
        ) return null
        return tz
    }

    /** Relit les valeurs en arrière-plan si elles sont absentes ou anciennes ; onResult sur le thread de l'exécuteur. */
    fun refresh(ctx: Context, executor: Executor, onResult: (Int) -> Unit) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = Settings(app)
        val fp = fingerprint(s.mode, s.server, s.user) ?: return
        if (prefs.getString(KEY_FP, null) != fp) {
            // Valeurs lues pour un autre compte : inconnues jusqu'à la nouvelle lecture.
            val e = prefs.edit()
            CACHE_KEYS.forEach { e.remove(it) }
            e.apply()
        }
        val fresh = System.currentTimeMillis() - prefs.getLong(KEY_AT, 0L) < MAX_AGE_MS
        if (fresh && prefs.contains(KEY) && prefs.contains(KEY_TZ)) return
        runCatching {
            executor.execute {
                val info = runCatching { fetch(s) }.onFailure { Log.w(TAG, "user_info illisible", it) }.getOrNull()
                    ?: return@execute
                // Compte changé pendant la requête : réponse ignorée.
                if (currentFingerprint(app) != fp) return@execute
                val e = prefs.edit().putLong(KEY_AT, System.currentTimeMillis()).putString(KEY_FP, fp)
                // max_connections absent : inconnu (pas une valeur restée d'une lecture précédente).
                info.maxConnections?.let { e.putInt(KEY, it) } ?: e.remove(KEY)
                // Fuseau absent de la réponse : chaîne vide mémorisée (pas de nouvelle requête à chaque fois).
                e.putString(KEY_TZ, info.timezone.orEmpty())
                e.apply()
                Log.i(TAG, "max_connections = ${info.maxConnections}, fuseau = ${info.timezone}")
                info.maxConnections?.let(onResult)
            }
        }
    }

    /** Pour les écrans hors lecteur (guide) : met le fuseau en cache s'il manque. */
    fun warmUp(ctx: Context) {
        refresh(ctx, { r -> Thread(r, "AccountInfo").start() }) {}
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun fetch(s: Settings): Info? {
        val url = XtreamClient.normalize(s.server) + "/player_api.php?username=${enc(s.user)}&password=${enc(s.pass)}"
        val c = Http.open(url)
        try {
            JsonReader(InputStreamReader(BufferedInputStream(c.inputStream), Charsets.UTF_8)).use { r ->
                r.isLenient = true
                if (r.peek() != JsonToken.BEGIN_OBJECT) return null
                var max: Int? = null
                var tz: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "user_info" -> if (r.peek() == JsonToken.BEGIN_OBJECT) {
                            max = Json.readFlat(r)["max_connections"]?.trim()?.toIntOrNull()
                        } else r.skipValue()
                        "server_info" -> if (r.peek() == JsonToken.BEGIN_OBJECT) {
                            tz = Json.readFlat(r)["timezone"]?.trim()?.takeIf { it.isNotEmpty() }
                        } else r.skipValue()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                return Info(max, tz)
            }
        } finally {
            c.disconnect()
        }
    }
}
