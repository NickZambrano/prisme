package com.fluxfr.tv.tvhome

import android.content.Context
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.CatalogCache
import com.fluxfr.tv.data.CatalogRepository
import com.fluxfr.tv.data.Episode
import com.fluxfr.tv.data.Progress
import com.fluxfr.tv.data.ProgressStore
import com.fluxfr.tv.data.Series
import com.fluxfr.tv.data.kidsOnly
import com.fluxfr.tv.player.PlayItem
import com.fluxfr.tv.player.Playables
import java.io.File

/**
 * Accès au catalogue hors de l'interface (recherche globale, Watch Next, liens profonds) :
 * le catalogue en mémoire s'il est chargé, sinon le cache disque (lu une fois, à appeler hors du fil principal).
 */
object CatalogAccess {
    @Volatile private var diskCache: Pair<Long, Catalog>? = null

    /** Catalogue en mémoire seulement (sans lecture disque). */
    fun peek(): Catalog? = (CatalogRepository.state.value as? CatalogRepository.State.Ready)?.catalog

    /** Catalogue en mémoire, sinon lu depuis le cache disque. Bloquant : jamais sur le fil principal. */
    fun get(ctx: Context): Catalog? {
        peek()?.let { return it }
        val file = File(ctx.applicationContext.filesDir, "catalog.json")
        if (!file.exists()) return null
        val stamp = file.lastModified()
        diskCache?.let { (at, c) -> if (at == stamp) return c }
        return synchronized(this) {
            diskCache?.let { (at, c) -> if (at == stamp) return@synchronized c }
            runCatching { CatalogCache.read(file) }.getOrNull()?.also { diskCache = stamp to it }
        }
    }

    fun sortedEpisodes(s: Series): List<Episode> =
        s.episodes.orEmpty().sortedWith(compareBy<Episode>({ it.season }, { it.number }))

    /** Série (avec épisodes connus) contenant cet épisode. */
    fun seriesOfEpisode(c: Catalog, seriesId: String?, episodeId: String): Series? {
        seriesId?.let { id -> c.seriesById[id]?.takeIf { s -> s.episodes?.any { it.id == episodeId } == true }?.let { return it } }
        return c.series.firstOrNull { s -> s.episodes?.any { it.id == episodeId } == true }
    }

    /** Épisode suivant (dans l'ordre saison / numéro), ou null. */
    fun nextEpisode(s: Series, episodeId: String): Episode? {
        val eps = sortedEpisodes(s)
        val i = eps.indexOfFirst { it.id == episodeId }
        return if (i >= 0) eps.getOrNull(i + 1) else null
    }

    /**
     * Profil Enfants : la clé de reprise (« movie:<id> » / « ep:<id> ») désigne-t-elle un programme du
     * catalogue jeunesse [kidsCatalog] ? [seriesIdHint] : série de l'épisode d'après la base des reprises
     * locale (les épisodes Xtream ne sont pas dans le catalogue). Toute autre clé est refusée.
     */
    fun kidsAllows(kidsCatalog: Catalog?, key: String, seriesIdHint: String?): Boolean {
        if (kidsCatalog == null) return false
        return when {
            key.startsWith("movie:") -> kidsCatalog.movieById.containsKey(key.removePrefix("movie:"))
            key.startsWith("ep:") -> {
                val id = key.removePrefix("ep:")
                (seriesIdHint != null && kidsCatalog.seriesById.containsKey(seriesIdHint)) ||
                    kidsCatalog.series.any { s -> s.episodes?.any { it.id == id } == true }
            }
            else -> false
        }
    }

    /** [kidsAllows] avec la série connue de la base des reprises. Bloquant : jamais sur le fil principal. */
    fun kidsAllows(ctx: Context, key: String, catalog: Catalog?): Boolean {
        val progress = runCatching { ProgressStore.get(ctx).get(key) }.getOrNull()
        return kidsAllows(catalog?.kidsOnly(), key, progress?.seriesId)
    }

    /**
     * File de lecture pour une clé de reprise (« movie:<id> » / « ep:<id> ») : la série entière pour un
     * épisode (enchaînement automatique), sinon l'élément seul. La position est reprise par le lecteur.
     */
    fun resolvePlay(ctx: Context, key: String, catalog: Catalog?): Pair<List<PlayItem>, Int>? {
        val progress = runCatching { ProgressStore.get(ctx).get(key) }.getOrNull()
        when {
            key.startsWith("movie:") -> {
                val id = key.removePrefix("movie:")
                catalog?.movieById?.get(id)?.let { return listOf(Playables.movie(it)) to 0 }
            }
            key.startsWith("ep:") -> {
                val id = key.removePrefix("ep:")
                val s = catalog?.let { seriesOfEpisode(it, progress?.seriesId, id) }
                if (s != null) {
                    val eps = sortedEpisodes(s)
                    val i = eps.indexOfFirst { it.id == id }
                    if (i >= 0) return Playables.episodes(s, eps) to i
                }
            }
        }
        return progress?.takeIf { it.url != null && (it.type == Progress.MOVIE || it.type == Progress.EPISODE) }
            ?.let { listOf(Playables.fromProgress(it)) to 0 }
    }
}
