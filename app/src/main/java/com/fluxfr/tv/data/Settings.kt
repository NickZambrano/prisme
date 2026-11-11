package com.fluxfr.tv.data

import android.content.Context
import com.fluxfr.tv.BuildConfig

class Settings(ctx: Context) {
    private val app = ctx.applicationContext
    private val p = app.getSharedPreferences("fluxfr", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = p.getString(k, d) ?: d
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()

    /** Préférence partagée entre les TV ([SettingsSync]) : enregistrée, puis synchronisée si elle a changé. */
    private fun putShared(k: String, v: String, d: String) {
        if (p.contains(k) && str(k, d) == v) return
        if (!p.contains(k) && v == d) return
        put(k, v)
        SettingsSync.touch(app, SettingsSync.PREFS_DOMAIN)
    }

    private fun putShared(k: String, v: Boolean) {
        if (p.getBoolean(k, false) == v && (p.contains(k) || !v)) return
        p.edit().putBoolean(k, v).apply()
        SettingsSync.touch(app, SettingsSync.PREFS_DOMAIN)
    }

    var mode: String
        get() = str("mode", MODE_XTREAM)
        set(v) = put("mode", v)
    var server: String
        get() = str("server", BuildConfig.DEFAULT_SERVER)
        set(v) = put("server", v)
    var user: String
        get() = str("user", BuildConfig.DEFAULT_USER)
        set(v) = put("user", v)
    var pass: String
        get() = str("pass", BuildConfig.DEFAULT_PASS)
        set(v) = put("pass", v)
    var m3uUrl: String
        get() = str("m3u", "")
        set(v) = put("m3u", v)

    /** Mots qui désignent du contenu français (catégorie, ou préfixe du nom). */
    var frKeywords: String
        get() = str("fr", DEFAULT_FR)
        set(v) = putShared("fr", v, DEFAULT_FR)

    /** Mots qui excluent un contenu (VOSTFR, adulte…). */
    var excludeKeywords: String
        get() = str("exclude", DEFAULT_EXCLUDE)
        set(v) = putShared("exclude", v, DEFAULT_EXCLUDE)

    var userAgent: String
        get() = str("ua", DEFAULT_UA).ifBlank { DEFAULT_UA }
        set(v) = putShared("ua", v, DEFAULT_UA)

    /** Films/séries : n'accepter que ce qui porte une mention HD/FHD/4K explicite. */
    var strictVodQuality: Boolean
        get() = p.getBoolean("strictVod", false)
        set(v) = putShared("strictVod", v)

    /** Chaînes : accepter aussi celles sans mention de qualité. */
    var keepUnknownLive: Boolean
        get() = p.getBoolean("unknownLive", false)
        set(v) = putShared("unknownLive", v)

    /** Chaînes : mettre la version 4K en premier (souvent des flux non officiels chez les fournisseurs). */
    var preferUhdLive: Boolean
        get() = p.getBoolean("uhdLive", false)
        set(v) = putShared("uhdLive", v)

    /**
     * Onglet « Fichiers » (disque local, clés USB et partages réseau) dans le menu. Propre à cet appareil (pas
     * synchronisé entre TV) ; l'état suivi par l'interface est [com.fluxfr.tv.data.files.FilesStore.enabled].
     */
    var filesTab: Boolean
        get() = com.fluxfr.tv.data.files.FilesStore.isEnabled(app)
        set(v) = com.fluxfr.tv.data.files.FilesStore.setEnabled(app, v)

    val isConfigured: Boolean
        get() = if (mode == MODE_M3U) m3uUrl.isNotBlank() else server.isNotBlank() && user.isNotBlank()

    fun logout() {
        // Valeurs vides explicites : sinon les identifiants par défaut reviendraient.
        p.edit().putString("server", "").putString("user", "").putString("pass", "").putString("m3u", "")
            // Limites du compte en cache (lecteur) : propres à l'ancien compte, relues pour le suivant.
            .remove("maxConnections").remove("maxConnectionsAt").remove("serverTimezone").remove("accountLimitsFor")
            .apply()
    }

    companion object {
        const val MODE_XTREAM = "xtream"
        const val MODE_M3U = "m3u"
        const val DEFAULT_FR = "FR, FRA, FRANCE, FRENCH, VF, VFF, VFQ, TRUEFRENCH"
        const val DEFAULT_EXCLUDE = "VOSTFR, VOST, XXX, ADULT, ADULTE, ADULTS, 18+, +18"
        const val DEFAULT_UA = "Prisme/1.0 (Linux; Android TV)"
    }
}
