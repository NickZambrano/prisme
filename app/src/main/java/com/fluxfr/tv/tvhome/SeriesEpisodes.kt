package com.fluxfr.tv.tvhome

import android.content.Context
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.CatalogRepository
import com.fluxfr.tv.data.Episode

/**
 * Épisodes d'une série pour la rangée Watch Next, hors interface : ceux du catalogue (listes M3U), sinon ceux
 * du serveur Xtream (get_series_info, avec adresses de lecture), via [CatalogRepository.seriesEpisodes]
 * (petit cache). Bloquant (réseau) : uniquement sur le fil de [WatchNextSync].
 */
internal object SeriesEpisodes {
    fun episodes(ctx: Context, seriesId: String?, catalog: Catalog?): List<Episode>? {
        if (seriesId.isNullOrBlank()) return null
        return runCatching { CatalogRepository.seriesEpisodes(ctx, seriesId, catalog) }.getOrNull()
    }
}

/** Règles pures (testées) de la rangée Watch Next. */
internal object WatchNextRules {
    private const val DISMISSED = "dp:"
    private const val LEGACY_DISMISSED = "d:"

    /** Clé du retrait d'un emplacement par l'utilisateur, propre au profil. */
    fun dismissKey(profile: String, slot: String) = "$DISMISSED$profile|$slot"

    /** Emplacement d'une ancienne clé de retrait (commune à tous les profils), ou null. */
    fun legacyDismissSlot(key: String): String? =
        if (key.startsWith(LEGACY_DISMISSED)) key.removePrefix(LEGACY_DISMISSED).takeIf { it.isNotEmpty() } else null

    fun find(eps: List<Episode>, id: String): Episode? = eps.firstOrNull { it.id == id }

    /**
     * Épisode suivant [currentId] dans [eps] (ordre saison / numéro). Si l'épisode n'y est pas (id différent
     * d'un serveur à l'autre), le premier épisode après (saison [season], numéro [number]).
     */
    fun next(eps: List<Episode>, currentId: String, season: Int, number: Int): Episode? {
        val sorted = eps.sortedWith(compareBy<Episode>({ it.season }, { it.number }))
        val i = sorted.indexOfFirst { it.id == currentId }
        if (i >= 0) return sorted.getOrNull(i + 1)
        if (number <= 0) return null
        val code = season * 10_000 + number
        return sorted.firstOrNull { it.season * 10_000 + it.number > code }
    }
}
