package com.fluxfr.tv.data

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Informations enrichies d'un film ou d'une série, obtenues en arrière-plan (get_vod_info,
 * liste des séries) et mémorisées sur disque : genres normalisés, identifiants
 * TMDB/IMDB (pour retrouver les titres TMDB), année.
 *
 * Clés : "movie:<id>" ou "series:<id>" (ids du fournisseur).
 */
data class Meta(
    val genres: List<String> = emptyList(),
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val year: Int? = null,
)

object VodMeta {
    fun movieKey(id: String) = "movie:$id"
    fun seriesKey(id: String) = "series:$id"

    private val map = ConcurrentHashMap<String, Meta>()
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "vod_meta.tsv")

    /** Format : clé \t genres séparés par | \t tmdb \t imdb \t année */
    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                file(ctx).forEachLine { line ->
                    val p = line.split('\t')
                    if (p.size >= 5) {
                        map[p[0]] = Meta(
                            genres = p[1].split('|').filter { it.isNotBlank() },
                            tmdbId = p[2].ifBlank { null },
                            imdbId = p[3].ifBlank { null },
                            year = p[4].toIntOrNull(),
                        )
                    }
                }
            }
            loaded = true
        }
    }

    fun get(key: String): Meta? = map[key]

    fun has(key: String) = map.containsKey(key)

    fun put(key: String, meta: Meta) {
        map[key] = meta
    }

    /** Écrit tout sur disque (à appeler après un lot d'ajouts, hors du thread principal). */
    @Synchronized
    fun save(ctx: Context) {
        runCatching {
            val tmp = File(ctx.filesDir, "vod_meta.tmp")
            tmp.bufferedWriter().use { w ->
                for ((k, m) in map) {
                    w.append(k).append('\t').append(m.genres.joinToString("|")).append('\t')
                        .append(m.tmdbId ?: "").append('\t').append(m.imdbId ?: "").append('\t')
                        .append(m.year?.toString() ?: "").append('\n')
                }
            }
            if (!tmp.renameTo(file(ctx))) { file(ctx).delete(); tmp.renameTo(file(ctx)) }
        }
    }

    fun size() = map.size
}
