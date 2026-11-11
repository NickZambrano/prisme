package com.fluxfr.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// =============================================================================== Modèle

/** Film ou série du catalogue proposé par les recommandations. */
sealed class RecoItem {
    abstract val key: String
    abstract val name: String
    abstract val quality: Quality
    abstract val added: Long

    data class M(val movie: Movie) : RecoItem() {
        override val key get() = VodMeta.movieKey(movie.id)
        override val name get() = movie.name
        override val quality get() = movie.quality
        override val added get() = movie.added
    }

    data class S(val series: Series) : RecoItem() {
        override val key get() = VodMeta.seriesKey(series.id)
        override val name get() = series.name
        override val quality get() = series.quality
        override val added get() = series.added
    }

    fun isKind(kind: RecoKind?): Boolean = when (kind) {
        null -> true
        RecoKind.MOVIE -> this is M
        RecoKind.SERIES -> this is S
    }
}

enum class RecoKind { MOVIE, SERIES }

/** Un titre renvoyé par TMDB (liste de recommandations / similaires / recherche). */
data class TmdbHit(
    /** [Tmdb.MOVIE] ou [Tmdb.TV]. */
    val type: String,
    val id: String,
    val title: String,
    val original: String?,
    val year: Int?,
    /** Genres TMDB (genres « séries » ramenés aux genres « films »). */
    val genres: Set<Int>,
    val popularity: Double = 0.0,
    val vote: Double = 0.0,
    val votes: Int = 0,
)

/** Fiche détaillée TMDB : genres, personnes (« c<id> » acteur, « d<id> » réalisateur / créateur), mots-clés, saga. */
data class TmdbInfo(
    val genres: Set<Int>,
    val people: Set<String>,
    val keywords: Set<Int>,
    val collection: String? = null,
)

/** Titre vraiment regardé (ou mis dans Ma liste) qui sert de point de départ. Clé : "movie:<id>" / "series:<id>". */
data class RecoSeed(
    val key: String,
    val title: String,
    val type: String,
    val weight: Double,
    val lastAt: Long,
    val favorite: Boolean = false,
)

class SeedData(val seed: RecoSeed, val recs: List<TmdbHit>, val similar: List<TmdbHit>, val info: TmdbInfo?)

/** Goûts du profil : poids (max = 1) par genre TMDB, personne et mot-clé. */
class Taste(val genres: Map<Int, Double>, val people: Map<String, Double>, val keywords: Map<Int, Double>) {
    companion object { val EMPTY = Taste(emptyMap(), emptyMap(), emptyMap()) }
}

/** Candidat noté. [group] : saga / franchise (diversité) ; [seed] : titre de départ qui l'a le plus apporté. */
data class RecoScored(
    val item: RecoItem,
    val score: Double,
    val genres: Set<Int>,
    val group: String,
    val seed: String?,
    /** "movie:<tmdb>" / "tv:<tmdb>". */
    val tmdb: String,
)

/** Rangées prêtes à afficher. */
class RecoRows(
    val forYou: List<RecoItem>,
    val because: List<Pair<String, List<RecoItem>>>,
    val genres: List<Pair<String, List<RecoItem>>>,
) {
    fun isEmpty() = forYou.isEmpty() && because.isEmpty() && genres.isEmpty()

    companion object { val EMPTY = RecoRows(emptyList(), emptyList(), emptyList()) }
}

// =============================================================================== Lecture des réponses TMDB

object TmdbParse {
    private fun num(v: Any?): Double? = (v as? Number)?.toDouble() ?: (v as? String)?.toDoubleOrNull()
    private fun idOf(v: Any?): String? = num(v)?.toLong()?.takeIf { it > 0 }?.toString()
    private fun yearOf(v: Any?): Int? = (v as? String)?.take(4)?.toIntOrNull()

    /** Genres « séries » TMDB ramenés aux genres « films » (Action & Aventure → Action + Aventure…). */
    fun normGenres(ids: Iterable<Int>): Set<Int> {
        val out = HashSet<Int>()
        for (g in ids) when (g) {
            10759 -> { out += 28; out += 12 }
            10765 -> { out += 878; out += 14 }
            10768 -> out += 10752
            10762 -> out += 10751
            else -> out += g
        }
        return out
    }

    fun hit(o: Map<*, *>, type: String): TmdbHit? {
        val movie = type == Tmdb.MOVIE
        val id = idOf(o["id"]) ?: return null
        val title = (o[if (movie) "title" else "name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val original = (o[if (movie) "original_title" else "original_name"] as? String)?.trim()
        return TmdbHit(
            type = type, id = id, title = title,
            original = original?.takeIf { it.isNotEmpty() && it != title },
            year = yearOf(o[if (movie) "release_date" else "first_air_date"]),
            genres = normGenres((o["genre_ids"] as? List<*>).orEmpty().mapNotNull { num(it)?.toInt() }),
            popularity = num(o["popularity"]) ?: 0.0,
            vote = num(o["vote_average"]) ?: 0.0,
            votes = num(o["vote_count"])?.toInt() ?: 0,
        )
    }

    /** Champ "results" d'une liste (recommandations, similaires, recherche). */
    fun hits(json: String?, type: String): List<TmdbHit> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { MiniJson.parse(json) as? Map<*, *> }.getOrNull() ?: return emptyList()
        return (root["results"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let { o -> hit(o, type) } }
    }

    /** Meilleur résultat d'une recherche : même année à un an près si elle est connue. */
    fun searchBest(json: String?, type: String, year: Int?): String? {
        val list = hits(json, type)
        if (year == null) return list.firstOrNull()?.id
        return list.take(5).firstOrNull { it.year != null && abs(it.year - year) <= 1 }?.id
    }

    /** Fiche /movie/{id} ou /tv/{id} avec append_to_response=credits,keywords. */
    fun info(json: String?, type: String): TmdbInfo? {
        if (json.isNullOrBlank()) return null
        val o = runCatching { MiniJson.parse(json) as? Map<*, *> }.getOrNull() ?: return null
        if (idOf(o["id"]) == null) return null
        val genres = normGenres((o["genres"] as? List<*>).orEmpty().mapNotNull { num((it as? Map<*, *>)?.get("id"))?.toInt() })
        val people = LinkedHashSet<String>()
        val credits = o["credits"] as? Map<*, *>
        (credits?.get("cast") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            .sortedBy { num(it["order"]) ?: 999.0 }.take(5)
            .forEach { c -> idOf(c["id"])?.let { people += "c$it" } }
        (credits?.get("crew") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            .filter { it["job"] == "Director" }.take(2)
            .forEach { c -> idOf(c["id"])?.let { people += "d$it" } }
        (o["created_by"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.take(2)
            .forEach { c -> idOf(c["id"])?.let { people += "d$it" } }
        val kw = o["keywords"] as? Map<*, *>
        val keywords = ((kw?.get("keywords") ?: kw?.get("results")) as? List<*>).orEmpty()
            .mapNotNull { num((it as? Map<*, *>)?.get("id"))?.toInt() }.take(40).toSet()
        val collection = idOf((o["belongs_to_collection"] as? Map<*, *>)?.get("id"))
        return TmdbInfo(genres, people, keywords, collection)
    }
}

// =============================================================================== Moteur (logique pure, testée)

object RecoEngine {
    const val DAY_MS = 86_400_000L
    const val HALF_LIFE_DAYS = 30.0
    /** Poids d'un titre de Ma liste (non regardé) par rapport à un film vu en entier aujourd'hui. */
    const val FAVORITE_WEIGHT = 0.35
    const val FOR_YOU_SIZE = 30
    const val BECAUSE_ROWS = 3
    const val BECAUSE_MIN = 6
    const val GENRE_ROWS = 2
    const val GENRE_ROW_MIN = 8
    const val ROW_MAX = 20

    /** Décroissance exponentielle : 1 aujourd'hui, 0,5 après [halfLifeDays] jours. */
    fun decay(ageMs: Long, halfLifeDays: Double = HALF_LIFE_DAYS): Double =
        0.5.pow(max(0L, ageMs).toDouble() / (halfLifeDays * DAY_MS))

    /** Score d'un rang dans une liste TMDB (0 = premier). */
    fun rankScore(rank: Int): Double = 1.0 / sqrt(1.0 + rank)

    /** Date d'ajout au catalogue en ms (Xtream la donne en secondes). */
    fun addedMs(added: Long): Long = if (added in 1 until 100_000_000_000L) added * 1000 else added

    /**
     * Titres de départ : films terminés ou vus à plus de 30 %, séries dont au moins 2 épisodes ont été vus
     * (ou 1 terminé), puis Ma liste (poids plus faible). Poids = complétion × récence (demi-vie 30 jours).
     */
    fun seeds(rows: List<Progress>, favorites: List<String>, now: Long, titleOf: (String) -> String?): List<RecoSeed> {
        val out = LinkedHashMap<String, RecoSeed>()
        for (p in rows) {
            if (p.type != Progress.MOVIE) continue
            val f = p.fraction.toDouble()
            if (!p.finished && f <= 0.30) continue
            val completion = if (p.finished) 1.0 else 0.4 + 0.6 * ((f - 0.3) / 0.63).coerceIn(0.0, 1.0)
            val key = VodMeta.movieKey(p.itemId)
            val w = completion * decay(now - p.updated)
            val prev = out[key]
            if (prev == null || prev.weight < w) {
                out[key] = RecoSeed(key, titleOf(key) ?: CatalogTitles.cleanTitle(p.title), Tmdb.MOVIE, w, p.updated)
            }
        }
        rows.filter { it.type == Progress.EPISODE && it.seriesId != null }.groupBy { it.seriesId!! }.forEach { (sid, eps) ->
            val started = eps.distinctBy { it.season to it.episode }.count { it.finished || it.fraction >= 0.3f }
            val finished = eps.count { it.finished }
            if (started < 2 && finished < 1) return@forEach
            val last = eps.maxOf { it.updated }
            val completion = min(1.0, 0.5 + 0.1 * started)
            val key = VodMeta.seriesKey(sid)
            out[key] = RecoSeed(key, titleOf(key) ?: CatalogTitles.cleanTitle(eps.first().title), Tmdb.TV, completion * decay(now - last), last)
        }
        var i = 0
        for (key in favorites) {
            val type = when {
                key.startsWith("movie:") -> Tmdb.MOVIE
                key.startsWith("series:") -> Tmdb.TV
                else -> continue
            }
            val w = FAVORITE_WEIGHT * 0.5.pow(i++ / 10.0)
            val prev = out[key]
            if (prev != null) {
                out[key] = prev.copy(weight = prev.weight + 0.3 * w)
            } else {
                val title = titleOf(key) ?: continue
                out[key] = RecoSeed(key, title, type, w, 0, favorite = true)
            }
        }
        return out.values.sortedByDescending { it.weight }
    }

    /**
     * À ne pas proposer : films déjà vus (terminés ou > 70 %) ou entamés (dans « Continuer à regarder »),
     * séries commencées (elles y sont aussi).
     */
    fun excluded(rows: List<Progress>): Set<String> {
        val out = HashSet<String>()
        for (p in rows) when (p.type) {
            Progress.MOVIE -> if (p.finished || p.fraction > 0.7f || p.position > 30_000) out += VodMeta.movieKey(p.itemId)
            Progress.EPISODE -> p.seriesId?.let { out += VodMeta.seriesKey(it) }
        }
        return out
    }

    private fun <K> normalizeMax(m: Map<K, Double>): Map<K, Double> {
        val top = m.values.maxOrNull() ?: return emptyMap()
        if (top <= 0) return emptyMap()
        return m.mapValues { it.value / top }
    }

    /** Vecteur de goûts : genres, 5 premiers acteurs, réalisateur, mots-clés des titres de départ, pondérés. */
    fun taste(data: List<SeedData>): Taste {
        val g = HashMap<Int, Double>()
        val p = HashMap<String, Double>()
        val k = HashMap<Int, Double>()
        for (d in data) {
            val info = d.info ?: continue
            val w = d.seed.weight
            info.genres.forEach { g.merge(it, w, Double::plus) }
            info.people.forEach { p.merge(it, w, Double::plus) }
            info.keywords.forEach { k.merge(it, w, Double::plus) }
        }
        return Taste(normalizeMax(g), normalizeMax(p), normalizeMax(k))
    }

    /** Proximité avec les goûts (0..1) : cosinus des genres, personnes et mots-clés en commun. */
    fun tasteSim(t: Taste, genres: Set<Int>, info: TmdbInfo?): Double {
        var genre = 0.0
        if (genres.isNotEmpty() && t.genres.isNotEmpty()) {
            val dot = genres.sumOf { t.genres[it] ?: 0.0 }
            val norm = sqrt(t.genres.values.sumOf { it * it }) * sqrt(genres.size.toDouble())
            genre = if (norm > 0) dot / norm else 0.0
        }
        val people = if (info == null) 0.0 else min(1.0, info.people.sumOf { t.people[it] ?: 0.0 } / 1.5)
        val keywords = if (info == null) 0.0 else min(1.0, info.keywords.sumOf { t.keywords[it] ?: 0.0 } / 3.0)
        return 0.55 * genre + 0.25 * people + 0.2 * keywords
    }

    private val STOP = setOf("le", "la", "les", "l", "the", "a", "an", "un", "une", "des", "de", "du", "d", "et", "and", "of")
    private val ROMAN = setOf("ii", "iii", "iv", "vi", "vii", "viii", "ix", "x")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /** Clé de franchise grossière : deux premiers mots significatifs du titre (« Fast & Furious 7 » → « fast furious »). */
    fun franchiseKey(title: String): String {
        val base = searchKey(title).substringBefore(':').substringBefore(" - ")
        val words = base.split(NON_ALNUM).filter { it.isNotEmpty() && it !in STOP && it !in ROMAN && !it.all(Char::isDigit) }
        return if (words.isEmpty()) searchKey(title).trim() else words.take(2).joinToString(" ")
    }

    /**
     * Note chaque candidat : somme sur les titres de départ de poids × score de rang (similaires : × 0,6),
     * + proximité de goûts, + petit a priori (note TMDB, popularité), + petit bonus d'ajout récent au catalogue.
     */
    fun score(
        data: List<SeedData>,
        taste: Taste,
        resolve: (TmdbHit) -> RecoItem?,
        excluded: Set<String>,
        infoOf: (TmdbHit) -> TmdbInfo?,
        now: Long,
    ): List<RecoScored> {
        if (data.isEmpty()) return emptyList()
        val maxW = data.maxOf { it.seed.weight }.takeIf { it > 0 } ?: 1.0
        val seedKeys = data.mapTo(HashSet()) { it.seed.key }

        class Acc(val item: RecoItem, val hit: TmdbHit) {
            var collab = 0.0
            val bySeed = HashMap<String, Double>()
        }
        val acc = LinkedHashMap<String, Acc>()
        for (d in data) {
            val w = d.seed.weight / maxW
            for ((list, factor) in listOf(d.recs to 1.0, d.similar to 0.6)) {
                for ((rank, h) in list.withIndex()) {
                    val item = resolve(h) ?: continue
                    if (item.key in excluded || item.key in seedKeys) continue
                    val a = acc.getOrPut(item.key) { Acc(item, h) }
                    val c = w * factor * rankScore(rank)
                    a.collab += c
                    a.bySeed.merge(d.seed.key, c, Double::plus)
                }
            }
        }
        return acc.values.map { a ->
            val info = infoOf(a.hit)
            val genres = info?.genres?.takeIf { it.isNotEmpty() } ?: a.hit.genres
            val prior = 0.08 * (a.hit.vote / 10.0).coerceIn(0.0, 1.0) * min(1.0, a.hit.votes / 300.0) +
                0.04 * min(1.0, ln(1.0 + a.hit.popularity) / ln(501.0))
            val added = addedMs(a.item.added)
            val fresh = if (added > 0) 0.05 * decay(now - added, 60.0) else 0.0
            RecoScored(
                item = a.item,
                score = a.collab + 0.5 * tasteSim(taste, genres, info) + prior + fresh,
                genres = genres,
                group = info?.collection?.let { "col:$it" } ?: ("t:" + franchiseKey(a.hit.title)),
                seed = a.bySeed.maxByOrNull { it.value }?.key,
                tmdb = a.hit.type + ":" + a.hit.id,
            )
        }.sortedByDescending { it.score }
    }

    /** Ressemblance entre deux candidats : même saga / franchise = 1 ; sinon genres en commun et même titre de départ. */
    fun similarity(a: RecoScored, b: RecoScored): Double {
        if (a.group == b.group) return 1.0
        val union = (a.genres + b.genres).size
        val jaccard = if (union == 0) 0.0 else a.genres.count { it in b.genres }.toDouble() / union
        return 0.7 * jaccard + if (a.seed != null && a.seed == b.seed) 0.3 else 0.0
    }

    /** Réordonnancement MMR : pertinence moins ressemblance avec ce qui est déjà choisi (pas 10 films de la même saga). */
    fun diversify(list: List<RecoScored>, n: Int, lambda: Double = 0.7): List<RecoScored> {
        val pool = list.sortedByDescending { it.score }.take(n * 4).toMutableList()
        if (pool.isEmpty()) return emptyList()
        val top = pool.first().score.takeIf { it > 0 } ?: 1.0
        val out = ArrayList<RecoScored>(n)
        while (out.size < n && pool.isNotEmpty()) {
            var bestI = 0
            var best = Double.NEGATIVE_INFINITY
            for ((i, c) in pool.withIndex()) {
                val sim = out.maxOfOrNull { similarity(c, it) } ?: 0.0
                val v = lambda * c.score / top - (1 - lambda) * sim
                if (v > best) { best = v; bestI = i }
            }
            out += pool.removeAt(bestI)
        }
        return out
    }

    /** « Parce que tu as regardé « X » » : les titres de départ les plus forts (vus, récents), ≥ [minItems] titres chacun. */
    fun because(
        data: List<SeedData>,
        scored: List<RecoScored>,
        resolve: (TmdbHit) -> RecoItem?,
        excluded: Set<String>,
        rows: Int = BECAUSE_ROWS,
        minItems: Int = BECAUSE_MIN,
    ): List<Pair<String, List<RecoItem>>> {
        val scoreOf = scored.associate { it.item.key to it.score }
        val seedKeys = data.mapTo(HashSet()) { it.seed.key }
        val used = HashSet<String>()
        val titles = HashSet<String>()
        val out = ArrayList<Pair<String, List<RecoItem>>>()
        for (d in data.filter { !it.seed.favorite }.sortedByDescending { it.seed.weight }) {
            if (out.size >= rows) break
            if (searchKey(d.seed.title) in titles) continue
            val local = LinkedHashMap<String, Pair<RecoItem, Double>>()
            for ((list, factor) in listOf(d.recs to 1.0, d.similar to 0.6)) {
                for ((rank, h) in list.withIndex()) {
                    val item = resolve(h) ?: continue
                    if (item.key in excluded || item.key in seedKeys || item.key in used) continue
                    val v = factor * rankScore(rank) + 0.1 * (scoreOf[item.key] ?: 0.0)
                    local.merge(item.key, item to v) { a, b -> a.first to (a.second + b.second) }
                }
            }
            if (local.size < minItems) continue
            val items = local.values.sortedByDescending { it.second }.take(ROW_MAX).map { it.first }
            items.forEach { used += it.key }
            titles += searchKey(d.seed.title)
            out += d.seed.title to items
        }
        return out
    }

    private val GENRE_LABELS = mapOf(
        Genres.COMEDIE to "Comédies", Genres.CRIME to "Polars", Genres.DOCUMENTAIRE to "Documentaires",
        Genres.DRAME to "Drames", Genres.FAMILIAL to "À voir en famille", Genres.MYSTERE to "Mystères",
        Genres.ROMANCE to "Romances", Genres.THRILLER to "Thrillers", Genres.WESTERN to "Westerns",
    )

    fun genreTitle(g: String): String = (GENRE_LABELS[g] ?: g) + " pour toi"

    /**
     * « Comédies pour toi » : titres du catalogue du genre préféré du profil (genres [Genres] des titres de
     * départ), classés par score de recommandation puis par ajout récent. Au plus [maxRows] rangées.
     */
    fun genreRows(
        seeds: List<RecoSeed>,
        genresOf: (String) -> List<String>,
        universe: List<RecoItem>,
        excluded: Set<String>,
        scoreOf: Map<String, Double>,
        now: Long,
        maxRows: Int = GENRE_ROWS,
        minItems: Int = GENRE_ROW_MIN,
    ): List<Pair<String, List<RecoItem>>> {
        val hist = HashMap<String, Double>()
        for (s in seeds) genresOf(s.key).forEach { hist.merge(it, s.weight, Double::plus) }
        val total = hist.values.sum()
        if (total <= 0) return emptyList()
        val top = hist.entries.filter { it.value / total >= 0.15 }.sortedByDescending { it.value }.take(maxRows).map { it.key }
        val used = HashSet(excluded)
        seeds.forEach { used += it.key }
        val out = ArrayList<Pair<String, List<RecoItem>>>()
        for (g in top) {
            val pool = universe.filter { it.key !in used && it.quality != Quality.LOW && g in genresOf(it.key) }
            // Une seule version par titre : la meilleure qualité.
            val best = pool.groupBy { Trending.matchKey(it.name) }.values.map { v -> v.maxBy { it.quality.rank } }
            val ranked = best.sortedByDescending { (scoreOf[it.key] ?: 0.0) + 0.3 * decay(now - addedMs(it.added), 90.0) }
                .take(ROW_MAX)
            if (ranked.size < minItems) continue
            ranked.forEach { used += it.key }
            out += genreTitle(g) to ranked
        }
        return out
    }
}


// =============================================================================== Recommandations du profil

/**
 * « Pour toi », « Parce que tu as regardé « X » » et « Comédies pour toi » : recommandations par profil,
 * d'après ce qu'il a vraiment regardé (sans compte), complétées par TMDB (recommandations / titres similaires
 * des titres regardés, puis retrouvés dans le catalogue). Résultat gardé sur disque par profil ; recalculé
 * quand l'historique change, réseau au plus toutes les 30 min (le cache TMDB dure 7 jours).
 * Tout est à appeler hors du thread principal.
 */
object Reco {
    private const val NET_INTERVAL_MS = 30L * 60 * 1000
    private const val MAX_SEEDS = 12
    private const val MAX_REQUESTS = 60
    private const val MAX_CANDIDATE_DETAILS = 16
    private const val KEEP_SCORED = 150

    private class Entry(val key: String, val score: Double, val genres: Set<Int>, val group: String, val seed: String?, val tmdb: String)

    private class Snapshot(
        val sig: String,
        val at: Long,
        val netAt: Long,
        val scored: List<Entry>,
        val because: List<Pair<String, List<String>>>,
        val genres: List<Pair<String, List<String>>>,
    )

    private val mutex = Mutex()
    private val mem = ConcurrentHashMap<String, Snapshot>()

    suspend fun forYou(ctx: Context, catalog: Catalog, kind: RecoKind? = null): List<RecoItem> = rows(ctx, catalog, kind).forYou

    suspend fun becauseYouWatched(ctx: Context, catalog: Catalog, kind: RecoKind? = null): List<Pair<String, List<RecoItem>>> =
        rows(ctx, catalog, kind).because

    suspend fun topGenresRows(ctx: Context, catalog: Catalog, kind: RecoKind? = null): List<Pair<String, List<RecoItem>>> =
        rows(ctx, catalog, kind).genres

    /** Rangées à jour (calcul si l'historique a changé ou si le résultat a plus de 30 min). */
    suspend fun rows(ctx: Context, catalog: Catalog, kind: RecoKind? = null): RecoRows {
        val snap = snapshot(ctx.applicationContext, catalog)
        return withContext(Dispatchers.Default) { toRows(snap, catalog, kind) }
    }

    /** Dernières rangées connues du profil actif (mémoire, sinon disque), sans calcul : affichage immédiat. */
    fun peek(ctx: Context, catalog: Catalog, kind: RecoKind? = null): RecoRows? {
        val profile = Profiles.current.value
        val snap = mem[profile] ?: readSnapshot(ctx.applicationContext, profile)?.also { mem[profile] = it } ?: return null
        return toRows(snap, catalog, kind)
    }

    private fun itemOf(catalog: Catalog, key: String): RecoItem? {
        val id = key.substringAfter(':')
        return when (key.substringBefore(':')) {
            "movie" -> catalog.movieById[id]?.let { RecoItem.M(it) }
            "series" -> catalog.seriesById[id]?.let { RecoItem.S(it) }
            else -> null
        }
    }

    private fun toRows(snap: Snapshot, catalog: Catalog, kind: RecoKind?): RecoRows {
        fun item(k: String) = itemOf(catalog, k)?.takeIf { it.isKind(kind) }
        val scored = snap.scored.mapNotNull { e -> item(e.key)?.let { RecoScored(it, e.score, e.genres, e.group, e.seed, e.tmdb) } }
        val forYou = RecoEngine.diversify(scored, RecoEngine.FOR_YOU_SIZE).map { it.item }
        fun rows(list: List<Pair<String, List<String>>>, minItems: Int) = list.mapNotNull { (t, keys) ->
            keys.mapNotNull(::item).takeIf { it.size >= minItems }?.let { t to it }
        }
        return RecoRows(forYou, rows(snap.because, RecoEngine.BECAUSE_MIN), rows(snap.genres, RecoEngine.GENRE_ROW_MIN))
    }

    /** Ce qui change les recommandations : titres vus / entamés (par tranche de 10 %), Ma liste, catalogue. */
    private fun signature(rows: List<Progress>, favs: List<String>, catalog: Catalog): String {
        val sb = StringBuilder()
        sb.append(catalog.fetchedAt).append('|').append(catalog.movies.size).append('|').append(catalog.series.size)
        for (p in rows.sortedBy { it.key }) {
            sb.append('|').append(p.key).append(':').append(if (p.finished) "f" else (p.fraction * 10).toInt().toString())
        }
        favs.forEach { sb.append('|').append(it) }
        val h = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray())
        return h.joinToString("") { "%02x".format(it) }
    }

    private suspend fun snapshot(ctx: Context, catalog: Catalog): Snapshot = mutex.withLock {
        withContext(Dispatchers.IO) {
            VodMeta.load(ctx)
            Favorites.load(ctx)
            val profile = Profiles.current.value
            val rows = ProgressStore.get(ctx).historyOf(profile)
            val favs = Favorites.items.value
            // Graines de l'historique Netflix importé ([NetflixImport]) : comptent dans la signature.
            val sig = signature(rows, favs + NetflixSeeds.stamp(ctx, profile) + RecoIgnore.stamp(ctx, profile), catalog)
            val now = System.currentTimeMillis()
            val prev = mem[profile] ?: readSnapshot(ctx, profile)
            if (prev != null && prev.sig == sig && now - prev.at in 0 until NET_INTERVAL_MS) {
                mem[profile] = prev
                return@withContext prev
            }
            val refreshStale = prev == null || now - prev.netAt !in 0 until NET_INTERVAL_MS
            val snap = compute(
                ctx, catalog, rows, favs, sig, now, refreshStale, prev?.netAt ?: 0L, NetflixSeeds.read(ctx, profile),
                RecoIgnore.keys(ctx, profile),
            )
            mem[profile] = snap
            writeSnapshot(ctx, profile, snap)
            snap
        }
    }

    private suspend fun compute(
        ctx: Context,
        catalog: Catalog,
        rows: List<Progress>,
        favs: List<String>,
        sig: String,
        now: Long,
        refreshStale: Boolean,
        lastNet: Long,
        netflix: List<NfSeed> = emptyList(),
        ignored: Set<String> = emptySet(),
    ): Snapshot {
        val all = catalog.unfiltered
        fun nameOf(key: String): String? {
            if (key.startsWith("tmdb:")) return netflix.firstOrNull { "tmdb:" + it.tmdb == key }?.title
            val id = key.substringAfter(':')
            return if (key.startsWith("movie:")) all.movieById[id]?.name else all.seriesById[id]?.name
        }
        // Titres vus sur Netflix mais absents du catalogue : graines « comme un titre fini » (avec récence).
        val seeds = (RecoEngine.seeds(rows, favs, now) { k -> nameOf(k)?.let(CatalogTitles::cleanTitle) } +
            NetflixSeeds.recoSeeds(netflix, now))
            // « Pas pour moi » : titres écartés par le profil ([RecoIgnore]).
            .filterNot { RecoIgnore.matches(ignored, it.title) }
            .sortedByDescending { it.weight }
        // Déjà vus sur Netflix : jamais proposés.
        val watchedTmdb = netflix.mapTo(HashSet()) { it.tmdb }
        // Démarrage à froid : rien à recommander (Top 10 / Tendances s'en chargent).
        if (seeds.isEmpty()) return Snapshot(sig, now, lastNet, emptyList(), emptyList(), emptyList())

        val session = Tmdb.Session(ctx, refreshStale, MAX_REQUESTS)
        val sem = Tmdb.gate
        val data = coroutineScope {
            seeds.take(MAX_SEEDS).map { s ->
                async(Dispatchers.IO) { sem.withPermit { runCatching { seedData(session, s, nameOf(s.key)) }.getOrNull() } }
            }.awaitAll()
        }.filterNotNull()

        val matcher = CatalogMatcher.of(catalog)
        val m = object {
            fun find(h: TmdbHit): RecoItem? = if (h.type + ":" + h.id in watchedTmdb) null else matcher.find(h)
        }
        val excluded = RecoEngine.excluded(rows) + seeds.map { it.key }
        val taste = RecoEngine.taste(data)
        val infos = ConcurrentHashMap<String, Pair<TmdbInfo?, Unit>>()
        fun infoOf(h: TmdbHit): TmdbInfo? =
            infos.getOrPut(h.type + ":" + h.id) { Tmdb.info(session, h.type, h.id, cacheOnly = true) to Unit }.first
        var scored = RecoEngine.score(data, taste, m::find, excluded, ::infoOf, now)

        // Fiches détaillées (acteurs, mots-clés, saga) des meilleurs candidats, avec le budget restant : affine le score.
        val missing = scored.take(40).filter { infos[it.tmdb]?.first == null }.take(min(MAX_CANDIDATE_DETAILS, session.remaining))
        if (missing.isNotEmpty() && !session.offline) {
            coroutineScope {
                missing.map { c ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            val type = c.tmdb.substringBefore(':')
                            val info = runCatching { Tmdb.info(session, type, c.tmdb.substringAfter(':'), cacheOnly = false) }.getOrNull()
                            if (info != null) infos[c.tmdb] = info to Unit
                        }
                    }
                }.awaitAll()
            }
            scored = RecoEngine.score(data, taste, m::find, excluded, ::infoOf, now)
        }

        val because = RecoEngine.because(data, scored, m::find, excluded)
        val universe = ArrayList<RecoItem>(catalog.movies.size + catalog.series.size)
        catalog.movies.forEach { if (it.quality != Quality.LOW) universe += RecoItem.M(it) }
        catalog.series.forEach { if (it.quality != Quality.LOW) universe += RecoItem.S(it) }
        val genres = RecoEngine.genreRows(
            seeds, { VodMeta.get(it)?.genres.orEmpty() }, universe, excluded,
            scored.associate { it.item.key to it.score }, now,
        )
        return Snapshot(
            sig, now, if (refreshStale) now else lastNet,
            scored.take(KEEP_SCORED).map { Entry(it.item.key, it.score, it.genres, it.group, it.seed, it.tmdb) },
            because.map { (t, l) -> t to l.map { it.key } },
            genres.map { (t, l) -> t to l.map { it.key } },
        )
    }

    /** Identifiant TMDB d'un titre de départ (fournisseur, sinon recherche par titre + année, mémorisée). */
    private fun tmdbIdFor(session: Tmdb.Session, s: RecoSeed, name: String?): String? {
        // Graine de l'historique Netflix : identifiant TMDB déjà connu ("tmdb:<type>:<id>").
        if (s.key.startsWith("tmdb:")) return s.key.substringAfterLast(':').takeIf { it.isNotBlank() }
        VodMeta.get(s.key)?.tmdbId?.trim()?.takeIf { (it.toLongOrNull() ?: 0) > 0 }?.let { return it }
        Tmdb.resolvedId(session.ctx, s.key)?.let { return it }
        val raw = name ?: return null
        val title = CatalogTitles.cleanTitle(raw).ifBlank { return null }
        val year = VodMeta.get(s.key)?.year ?: CatalogTitles.yearIn(raw)
        val params = buildList {
            add("query" to title)
            if (year != null) add((if (s.type == Tmdb.MOVIE) "year" else "first_air_date_year") to year.toString())
        }
        val json = Tmdb.get(session, "/search/${s.type}", *params.toTypedArray())
        val id = TmdbParse.searchBest(json, s.type, year) ?: return null
        Tmdb.saveResolved(session.ctx, s.key, id)
        return id
    }

    private fun seedData(session: Tmdb.Session, s: RecoSeed, name: String?): SeedData? {
        val id = tmdbIdFor(session, s, name) ?: return null
        val t = s.type
        val recs = TmdbParse.hits(Tmdb.get(session, "/$t/$id/recommendations"), t)
        val similar = TmdbParse.hits(Tmdb.get(session, "/$t/$id/similar"), t)
        val info = Tmdb.info(session, t, id, cacheOnly = false)
        if (recs.isEmpty() && similar.isEmpty() && info == null) return null
        return SeedData(s, recs, similar, info)
    }

    /**
     * Oublie les recommandations gardées pour [profile] (mémoire et disque) : après l'annulation d'un import
     * Netflix, rien de ce qui en venait ne doit rester affiché ; le prochain [rows] recalcule.
     */
    fun invalidate(ctx: Context, profile: String) {
        mem.remove(profile)
        runCatching { file(ctx.applicationContext, profile).delete() }
    }

    // ---- Disque : files/reco/<profil>.json

    private fun file(ctx: Context, profile: String) =
        File(File(ctx.filesDir, "reco").also { it.mkdirs() }, profile.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private fun writeSnapshot(ctx: Context, profile: String, s: Snapshot) {
        runCatching {
            val json = MiniJson.write(
                mapOf(
                    "sig" to s.sig, "at" to s.at, "net" to s.netAt,
                    "s" to s.scored.map { mapOf("k" to it.key, "v" to it.score, "g" to it.genres.toList(), "f" to it.group, "o" to it.seed, "t" to it.tmdb) },
                    "b" to s.because.map { mapOf("t" to it.first, "k" to it.second) },
                    "r" to s.genres.map { mapOf("t" to it.first, "k" to it.second) },
                )
            )
            val f = file(ctx, profile)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private fun readSnapshot(ctx: Context, profile: String): Snapshot? = runCatching {
        val f = file(ctx, profile)
        if (!f.exists()) return null
        val o = MiniJson.parse(f.readText()) as Map<*, *>
        fun rows(k: String) = (o[k] as? List<*>).orEmpty().mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            (m["t"] as? String ?: return@mapNotNull null) to (m["k"] as? List<*>).orEmpty().filterIsInstance<String>()
        }
        Snapshot(
            sig = o["sig"] as? String ?: return null,
            at = (o["at"] as? Number)?.toLong() ?: 0L,
            netAt = (o["net"] as? Number)?.toLong() ?: 0L,
            scored = (o["s"] as? List<*>).orEmpty().mapNotNull { r ->
                val m = r as? Map<*, *> ?: return@mapNotNull null
                Entry(
                    key = m["k"] as? String ?: return@mapNotNull null,
                    score = (m["v"] as? Number)?.toDouble() ?: 0.0,
                    genres = (m["g"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() }.toSet(),
                    group = m["f"] as? String ?: "",
                    seed = m["o"] as? String,
                    tmdb = m["t"] as? String ?: "",
                )
            },
            because = rows("b"),
            genres = rows("r"),
        )
    }.getOrNull()
}
