package com.fluxfr.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Listes publiques TMDB (tendances de la semaine, populaires, mieux notés, au cinéma / en cours de diffusion),
 * en français pour la France, cache disque 6 h (hors ligne : dernier cache connu), puis retrouvées dans le
 * catalogue affiché ([CatalogMatcher] : meilleure version, jamais une copie basse qualité). Une liste avec
 * moins de [MIN_MATCHES] titres retrouvés n'est pas affichée. Profil Enfants : le catalogue reçu est déjà filtré.
 * Réseau sur IO, correspondance sur Default : jamais sur le thread principal.
 */
object TmdbLists {
    private const val MIN_MATCHES = 4
    private const val MAX_ITEMS = 30
    private const val PAGES = 3
    private const val MAX_REQUESTS = 40

    private class Spec(val title: String, val type: String, val path: String, val region: Boolean)

    private val TREND_MOVIES = Spec("Tendances films", Tmdb.MOVIE, "/trending/movie/week", region = false)
    private val TREND_SERIES = Spec("Tendances séries", Tmdb.TV, "/trending/tv/week", region = false)

    private val MOVIE_SHELVES = listOf(
        TREND_MOVIES,
        Spec("Films populaires", Tmdb.MOVIE, "/movie/popular", region = true),
        Spec("Films les mieux notés", Tmdb.MOVIE, "/movie/top_rated", region = true),
        Spec("Au cinéma", Tmdb.MOVIE, "/movie/now_playing", region = true),
    )

    private val SERIES_SHELVES = listOf(
        TREND_SERIES,
        Spec("Séries populaires", Tmdb.TV, "/tv/popular", region = false),
        Spec("Séries les mieux notées", Tmdb.TV, "/tv/top_rated", region = false),
        Spec("En cours de diffusion", Tmdb.TV, "/tv/on_the_air", region = false),
    )

    /** Derniers résultats par catalogue affiché : retour sur un écran sans rangée vide le temps du calcul. */
    private val memo = ConcurrentHashMap<String, Pair<Catalog, List<Any>>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> peek(id: String, catalog: Catalog): List<T>? =
        memo[id]?.takeIf { it.first === catalog }?.second as? List<T>

    /** Dernières tendances films connues pour ce catalogue (mémoire), sans calcul. */
    fun peekMovieTrending(catalog: Catalog): List<Movie> = peek<Movie>("tm", catalog).orEmpty()
    fun peekSeriesTrending(catalog: Catalog): List<Series> = peek<Series>("ts", catalog).orEmpty()
    fun peekMovieShelves(catalog: Catalog): List<Shelf<Movie>> = peek<Shelf<Movie>>("sm", catalog).orEmpty()
    fun peekSeriesShelves(catalog: Catalog): List<Shelf<Series>> = peek<Shelf<Series>>("ss", catalog).orEmpty()

    /** « Tendances films » (semaine), retrouvés dans le catalogue ; vide si moins de 4 titres. */
    suspend fun movieTrending(ctx: Context, catalog: Catalog): List<Movie> {
        val hits = fetch(ctx, listOf(TREND_MOVIES)).single()
        val out = withContext(Dispatchers.Default) {
            CatalogMatcher.of(catalog).movies(hits, MAX_ITEMS).takeIf { it.size >= MIN_MATCHES }.orEmpty()
        }
        memo["tm"] = catalog to out
        return out
    }

    /** « Tendances séries » (semaine), retrouvées dans le catalogue ; vide si moins de 4 titres. */
    suspend fun seriesTrending(ctx: Context, catalog: Catalog): List<Series> {
        val hits = fetch(ctx, listOf(TREND_SERIES)).single()
        val out = withContext(Dispatchers.Default) {
            CatalogMatcher.of(catalog).series(hits, MAX_ITEMS).takeIf { it.size >= MIN_MATCHES }.orEmpty()
        }
        memo["ts"] = catalog to out
        return out
    }

    /** Rangées de la page Films : tendances, populaires, mieux notés, au cinéma. */
    suspend fun movieShelves(ctx: Context, catalog: Catalog): List<Shelf<Movie>> {
        val lists = fetch(ctx, MOVIE_SHELVES)
        val out = withContext(Dispatchers.Default) {
            val m = CatalogMatcher.of(catalog)
            MOVIE_SHELVES.zip(lists).mapNotNull { (spec, hits) ->
                m.movies(hits, MAX_ITEMS).takeIf { it.size >= MIN_MATCHES }?.let { Shelf(spec.title, it) }
            }
        }
        memo["sm"] = catalog to out
        return out
    }

    /** Rangées de la page Séries : tendances, populaires, mieux notées, en cours de diffusion. */
    suspend fun seriesShelves(ctx: Context, catalog: Catalog): List<Shelf<Series>> {
        val lists = fetch(ctx, SERIES_SHELVES)
        val out = withContext(Dispatchers.Default) {
            val m = CatalogMatcher.of(catalog)
            SERIES_SHELVES.zip(lists).mapNotNull { (spec, hits) ->
                m.series(hits, MAX_ITEMS).takeIf { it.size >= MIN_MATCHES }?.let { Shelf(spec.title, it) }
            }
        }
        memo["ss"] = catalog to out
        return out
    }

    /** Pages 1 à 3 de chaque liste (au plus 4 requêtes simultanées), titres sans doublon dans l'ordre TMDB. */
    private suspend fun fetch(ctx: Context, specs: List<Spec>): List<List<TmdbHit>> = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        VodMeta.load(app)
        val session = Tmdb.Session(app, refreshStale = true, maxRequests = MAX_REQUESTS)
        coroutineScope {
            specs.map { spec ->
                (1..PAGES).map { page ->
                    async {
                        Tmdb.gate.withPermit {
                            val params = buildList {
                                add("page" to page.toString())
                                if (spec.region) add("region" to "FR")
                            }
                            val json = runCatching {
                                Tmdb.get(session, spec.path, *params.toTypedArray(), ttlMs = Tmdb.LIST_TTL_MS)
                            }.getOrNull()
                            TmdbParse.hits(json, spec.type)
                        }
                    }
                }
            }.map { pages -> pages.awaitAll().flatten().distinctBy { it.id } }
        }
    }
}
