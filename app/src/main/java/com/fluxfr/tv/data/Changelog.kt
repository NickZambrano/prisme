package com.fluxfr.tv.data

import android.content.Context
import com.fluxfr.tv.BuildConfig

/**
 * Journal des nouveautés (assets/changelog.txt) : montré une fois après chaque mise à jour, et consultable dans
 * Réglages > Mise à jour. Le même fichier fournit les notes de version publiées sur GitHub.
 */
object Changelog {
    data class Entry(val version: String, val items: List<String>)

    private const val PREFS = "changelog"
    private const val KEY_SEEN = "seen"

    /** « ## 3.5.1 » ouvre une version, « - texte » ajoute une nouveauté ; « # » = commentaire. */
    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        var version: String? = null
        val items = ArrayList<String>()
        fun flush() { version?.let { if (items.isNotEmpty()) out += Entry(it, items.toList()) }; items.clear() }
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("## ") -> { flush(); version = line.removePrefix("## ").trim() }
                line.startsWith("- ") && version != null -> items += line.removePrefix("- ").trim()
            }
        }
        flush()
        return out
    }

    /** Versions plus récentes que [seen] (dans l'ordre du fichier : la plus récente d'abord). */
    fun since(entries: List<Entry>, seen: String?): List<Entry> {
        if (seen == null) return emptyList()
        val i = entries.indexOfFirst { it.version == seen }
        return if (i < 0) entries.take(1) else entries.take(i)
    }

    fun all(ctx: Context): List<Entry> =
        runCatching { ctx.assets.open("changelog.txt").bufferedReader().use { parse(it.readText()) } }.getOrDefault(emptyList())

    /**
     * Nouveautés à montrer maintenant (une seule fois par version). Première installation : rien, on retient
     * simplement la version actuelle.
     */
    fun pending(ctx: Context): List<Entry> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = BuildConfig.VERSION_NAME
        val seen = p.getString(KEY_SEEN, null)
        if (seen == current) return emptyList()
        // Première installation (aucun profil, aucun historique) : pas de « nouveautés ».
        val firstRun = seen == null && !ctx.getSharedPreferences("fluxfr", Context.MODE_PRIVATE).contains("server")
        if (firstRun) { markSeen(ctx); return emptyList() }
        return since(all(ctx), seen ?: "")
    }

    fun markSeen(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SEEN, BuildConfig.VERSION_NAME).apply()
    }
}
