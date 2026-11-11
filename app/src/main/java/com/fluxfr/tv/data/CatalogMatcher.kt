package com.fluxfr.tv.data

/** Titre et année tirés d'un nom du catalogue (« FR - Dune (2021) » → « Dune », 2021). */
object CatalogTitles {
    private val YEAR = Regex("(19|20)\\d{2}")
    private val YEAR_SUFFIX = Regex("\\s*[-–(\\[]\\s*(19|20)\\d{2}\\s*[)\\]]?\\s*$")

    fun yearIn(name: String): Int? = YEAR.findAll(name).lastOrNull()?.value?.toIntOrNull()

    fun cleanTitle(name: String): String = YEAR_SUFFIX.replace(ContentFilter.cleanName(name), "").trim()
}

/**
 * Index d'une liste du catalogue (films ou séries) : par identifiant TMDB (connu via [VodMeta]) et par
 * titre normalisé. Les versions basse qualité (CAM, TS, SD…) n'y entrent jamais.
 * Construction coûteuse (catalogue entier) : hors du thread principal uniquement.
 */
class CatalogIndex<T>(
    items: List<T>,
    private val name: (T) -> String,
    private val meta: (T) -> Meta?,
    private val quality: (T) -> Quality = { Quality.UNKNOWN },
) {
    private val byTmdb = HashMap<String, MutableList<T>>()
    private val byKey = HashMap<String, MutableList<T>>()

    init {
        for (it in items) {
            if (quality(it) == Quality.LOW) continue
            meta(it)?.tmdbId?.let { id -> byTmdb.getOrPut(id) { ArrayList() } += it }
            byKey.getOrPut(Trending.matchKey(name(it))) { ArrayList() } += it
        }
    }

    /**
     * Meilleure version du catalogue (4K > FHD > HD > inconnue), jamais une version basse qualité :
     * par identifiant TMDB, sinon par titre (français, puis [altTitles] : titre original…) + année à un an près.
     */
    fun find(title: String, year: Int?, tmdbId: String?, altTitles: List<String> = emptyList()): T? {
        val byId = tmdbId?.let { byTmdb[it] }.orEmpty()
        if (byId.isNotEmpty()) {
            // Autres versions du même titre sans identifiant connu (ex. la 4K pas encore analysée).
            val y = year ?: byId.firstNotNullOfOrNull(::yearOf)
            val same = byId.flatMap { c -> byKey[Trending.matchKey(name(c))].orEmpty() }
                .filter { c -> meta(c)?.tmdbId.let { it == null || it == tmdbId } && yearMatches(c, y, strict = false) }
            return best((byId + same).distinct())
        }
        for (t in listOf(title) + altTitles) {
            val k = Trending.matchKey(t)
            if (k.length < 2) continue
            val candidates = byKey[k] ?: continue
            // Un identifiant TMDB différent connu : homonyme, pas le même titre.
            val pool = candidates.filter { c -> tmdbId == null || meta(c)?.tmdbId.let { it == null || it == tmdbId } }
            // Année connue des deux côtés : à un an près (dates de sortie différentes selon les pays).
            val hit = if (year == null) best(pool)
            else best(pool.filter { yearMatches(it, year, strict = true) }) ?: best(pool.filter { yearOf(it) == null })
            if (hit != null) return hit
        }
        return null
    }

    /** Nombre d'éléments indexés sous ce titre (homonymes, ou versions du même titre). */
    fun count(title: String): Int = byKey[Trending.matchKey(title)]?.size ?: 0

    private fun yearMatches(c: T, y: Int?, strict: Boolean): Boolean {
        if (y == null) return !strict
        val cy = yearOf(c) ?: return !strict
        return kotlin.math.abs(cy - y) <= 1
    }

    private fun best(list: List<T>): T? = list.maxByOrNull { quality(it).rank }

    private fun yearOf(c: T): Int? = meta(c)?.year ?: CatalogTitles.yearIn(name(c))
}

/**
 * Retrouve un titre TMDB dans le catalogue : par identifiant TMDB (connu via [VodMeta]), sinon par titre
 * (français ou original) + année à un an près ; meilleure version, jamais une copie basse qualité.
 * Construction coûteuse : hors du thread principal.
 */
class CatalogMatcher(movies: List<Movie>, series: List<Series>, meta: (String) -> Meta? = VodMeta::get) {
    private val mi = CatalogIndex(movies, { it.name }, { meta(VodMeta.movieKey(it.id)) }, { it.quality })
    private val si = CatalogIndex(series, { it.name }, { meta(VodMeta.seriesKey(it.id)) }, { it.quality })
    private val cache = HashMap<String, RecoItem?>()

    @Synchronized
    fun find(h: TmdbHit): RecoItem? {
        val k = h.type + ":" + h.id
        if (cache.containsKey(k)) return cache[k]
        val alt = listOfNotNull(h.original)
        val hit = if (h.type == Tmdb.MOVIE) mi.find(h.title, h.year, h.id, alt)?.let { RecoItem.M(it) }
        else si.find(h.title, h.year, h.id, alt)?.let { RecoItem.S(it) }
        cache[k] = hit
        return hit
    }

    /** Films retrouvés, dans l'ordre de [hits], sans doublon, au plus [limit]. */
    fun movies(hits: List<TmdbHit>, limit: Int): List<Movie> =
        hits.asSequence().mapNotNull { (find(it) as? RecoItem.M)?.movie }.distinctBy { it.id }.take(limit).toList()

    /** Séries retrouvées, dans l'ordre de [hits], sans doublon, au plus [limit]. */
    fun series(hits: List<TmdbHit>, limit: Int): List<Series> =
        hits.asSequence().mapNotNull { (find(it) as? RecoItem.S)?.series }.distinctBy { it.id }.take(limit).toList()

    companion object {
        @Volatile private var last: Pair<Catalog, CatalogMatcher>? = null

        /** Index du catalogue affiché, gardé tant que le catalogue ne change pas (partagé Reco / listes TMDB). */
        fun of(catalog: Catalog): CatalogMatcher {
            last?.takeIf { it.first === catalog }?.let { return it.second }
            return CatalogMatcher(catalog.movies, catalog.series).also { last = catalog to it }
        }
    }
}
