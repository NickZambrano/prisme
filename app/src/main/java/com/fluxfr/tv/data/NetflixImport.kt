package com.fluxfr.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

// =============================================================================== Fichier CSV (logique pure, testée)

/** Une date du fichier Netflix (sans heure). */
data class NfDate(val y: Int, val m: Int, val d: Int)

/** Une ligne de l'activité de visionnage : titre et instant (midi local du jour, + quelques secondes pour l'ordre). */
data class NfView(val title: String, val at: Long)

/**
 * Export « Activité de visionnage » de Netflix (netflix.com/viewingactivity → « Télécharger tout ») :
 * CSV `Title,Date`, champs entre guillemets, dates M/J/AA (format US), le plus récent d'abord.
 */
object NetflixCsv {
    /** Lignes du CSV (RFC 4180 : guillemets, virgules et sauts de ligne dans les champs, BOM, CRLF). */
    fun rows(text: String): List<List<String>> {
        val s = text.removePrefix("﻿")
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val f = StringBuilder()
        var quoted = false
        var i = 0
        fun endRow() {
            row.add(f.toString())
            f.setLength(0)
            if (!(row.size == 1 && row[0].isBlank())) out.add(row)
            row = ArrayList()
        }
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { f.append('"'); i++ } else quoted = false
                } else f.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row.add(f.toString()); f.setLength(0) }
                '\r' -> {}
                '\n' -> endRow()
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) endRow()
        return out
    }

    private val DATE = Regex("""^\s*(\d{1,4})[/.\-](\d{1,2})[/.\-](\d{1,4})\s*$""")

    /** Aujourd'hui (fuseau de l'appareil). */
    private fun currentDate(): NfDate = GregorianCalendar().let {
        NfDate(it.get(java.util.Calendar.YEAR), it.get(java.util.Calendar.MONTH) + 1, it.get(java.util.Calendar.DAY_OF_MONTH))
    }

    private fun code(d: NfDate) = d.y * 10_000 + d.m * 100 + d.d

    /**
     * Dates du fichier. Format décidé sur TOUT le fichier : AAAA-MM-JJ ; sinon J/M/AA si un premier nombre dépasse
     * 12, M/J/AA (US, celui de Netflix) si un second nombre dépasse 12 ; sinon (petit fichier ambigu) l'ordre qui
     * donne le moins de dates futures, puis des dates décroissantes de haut en bas (l'export commence par le
     * plus récent), et M/J à égalité. Année sur 2 chiffres = 20AA. Null si illisible ou dans le futur
     * (après [today] + 1 jour, marge de fuseau).
     */
    fun parseDates(raw: List<String>, today: NfDate = currentDate()): List<NfDate?> {
        val parts = raw.map { r -> DATE.find(r)?.groupValues?.drop(1)?.map { it.toInt() to it.length } }
        val short = parts.filter { p -> p != null && p[0].second <= 2 }.map { it!! }
        val dayFirst = when {
            short.any { it[0].first in 13..31 } -> true
            short.any { it[1].first in 13..31 } -> false
            short.isEmpty() -> false
            else -> {
                fun score(dm: Boolean): Pair<Int, Int> {
                    val dates = parts.map { p -> p?.let { build(it, dm) } }.filterNotNull()
                    val future = dates.count { isFuture(it, today) }
                    val increases = dates.zipWithNext().count { (a, b) -> code(b) > code(a) }
                    return future to increases
                }
                val us = score(false)
                val eu = score(true)
                compareValuesBy(eu, us, { it.first }, { it.second }) < 0
            }
        }
        return parts.map { p -> p?.let { build(it, dayFirst) }?.takeIf { !isFuture(it, today) } }
    }

    private fun build(p: List<Pair<Int, Int>>, dayFirst: Boolean): NfDate? {
        val (y, m, d) = when {
            p[0].second == 4 -> Triple(p[0].first, p[1].first, p[2].first)
            dayFirst -> Triple(p[2].first, p[1].first, p[0].first)
            else -> Triple(p[2].first, p[0].first, p[1].first)
        }
        val year = if (y < 100) 2000 + y else y
        return if (m !in 1..12 || d !in 1..31 || year !in 1990..2100) null else NfDate(year, m, d)
    }

    /** Après demain ([today] + 1 jour) : forcément une erreur de lecture. */
    private fun isFuture(d: NfDate, today: NfDate): Boolean {
        val c = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(today.y, today.m - 1, today.d); add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val limit = NfDate(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
        return code(d) > code(limit)
    }

    /** Midi (heure locale de [tz]) du jour [d], en ms. */
    fun noon(d: NfDate, tz: TimeZone): Long = GregorianCalendar(tz).apply {
        clear()
        set(d.y, d.m - 1, d.d, 12, 0, 0)
    }.timeInMillis

    /** Nom de colonne normalisé (« Title » → "title", « Épisode » → "episode"). */
    fun headerKey(h: String) = searchKey(h).filter { it.isLetterOrDigit() }

    /**
     * Instants des lignes datées [dates] (dans l'ordre du fichier, le plus récent d'abord) : midi local du jour,
     * + 1 s par titre vu plus tard le même jour. L'ordre d'une même journée est conservé, et un réimport donne
     * exactement les mêmes instants. Null pour une date illisible.
     */
    fun stamps(dates: List<NfDate?>, tz: TimeZone): List<Long?> {
        val perDay = HashMap<NfDate, Int>()
        dates.forEach { d -> if (d != null) perDay.merge(d, 1, Int::plus) }
        val seen = HashMap<NfDate, Int>()
        return dates.map { d ->
            if (d == null) return@map null
            val k = seen.merge(d, 1, Int::plus)!! - 1
            noon(d, tz) + (perDay.getValue(d) - 1 - k) * 1000L
        }
    }

    /** Visionnages du fichier, dans l'ordre du fichier (le plus récent d'abord), instants selon [stamps]. */
    fun parse(text: String, tz: TimeZone): List<NfView> {
        val all = rows(text)
        if (all.isEmpty()) return emptyList()
        val head = all[0].map(::headerKey)
        var ti = head.indexOfFirst { it == "title" || it == "titre" }
        var di = head.indexOfFirst { it == "date" }
        val body = if (ti >= 0 && di >= 0) all.drop(1) else { ti = 0; di = 1; all }
        val usable = body.filter { it.size > maxOf(ti, di) && it[ti].isNotBlank() }
        val at = stamps(parseDates(usable.map { it[di] }), tz)
        return usable.indices.mapNotNull { i -> at[i]?.let { NfView(NetflixTitles.clean(usable[i][ti]), it) } }
    }
}

// =============================================================================== Fichier Prime Video (logique pure, testée)

/**
 * Une ligne du fichier Prime Video. [kind] : [PrimeCsv.MOVIE], [PrimeCsv.EPISODE] (saison / numéro connus),
 * ou [PrimeCsv.SEASON] (épisodes d'une saison vus, sans détail).
 */
data class PvView(
    val title: String,
    val at: Long,
    val kind: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
)

/**
 * Fichier du bouton « Exporter Prime Video » (tools/prime-export.js) : CSV
 * `Title,Date,Type,Season,Episode,EpisodeTitle`, dates AAAA-MM-JJ, le plus récent d'abord.
 */
object PrimeCsv {
    const val MOVIE = "movie"
    const val EPISODE = "episode"
    const val SEASON = "season"

    /** Fichier Prime Video ? (reconnu à son en-tête : colonnes Title, Type, Season et Episode). */
    fun isPrime(text: String): Boolean {
        val first = NetflixCsv.rows(text.removePrefix("﻿").substringBefore('\n')).firstOrNull() ?: return false
        val h = first.map(NetflixCsv::headerKey)
        return "title" in h && "type" in h && "season" in h && "episode" in h
    }

    fun parse(text: String, tz: TimeZone): List<PvView> {
        val all = NetflixCsv.rows(text)
        if (all.size < 2) return emptyList()
        val head = all[0].map(NetflixCsv::headerKey)
        fun col(name: String) = head.indexOf(name)
        val ti = col("title"); val di = col("date"); val ki = col("type")
        val si = col("season"); val ei = col("episode"); val ni = col("episodetitle")
        if (ti < 0 || di < 0) return emptyList()
        fun List<String>.at(i: Int) = if (i in indices) this[i].trim() else ""
        val usable = all.drop(1).filter { it.at(ti).isNotBlank() }
        val at = NetflixCsv.stamps(NetflixCsv.parseDates(usable.map { it.at(di) }), tz)
        return usable.indices.mapNotNull { i ->
            val r = usable[i]
            val t = at[i] ?: return@mapNotNull null
            val season = r.at(si).toIntOrNull()?.takeIf { it in 0..999 }
            val episode = r.at(ei).toIntOrNull()?.takeIf { it in 0..9999 }
            val kind = when (r.at(ki).lowercase()) {
                MOVIE -> MOVIE
                EPISODE -> if (episode != null) EPISODE else SEASON
                SEASON -> SEASON
                else -> if (episode != null) EPISODE else if (season != null) SEASON else MOVIE
            }
            PvView(
                NetflixTitles.clean(r.at(ti)), t, kind,
                season = if (kind == MOVIE) null else season,
                episode = if (kind == EPISODE) episode else null,
                episodeTitle = if (kind == EPISODE) r.at(ni).takeIf { it.isNotBlank() }?.let(NetflixTitles::clean) else null,
            )
        }
    }

    /**
     * Regroupement : films (date la plus récente), séries avec leurs épisodes (saison / numéro donnés par le
     * fichier, aucun titre à analyser) et leurs saisons vues sans détail (jamais transformées en épisodes).
     */
    fun group(views: List<PvView>): ImportGroups {
        val g = ImportGroups()
        for (v in views) {
            when (v.kind) {
                MOVIE -> g.film(v.title, v.at)
                EPISODE -> g.show(v.title).watches += EpWatch(v.season?.let { "Saison $it" }, v.season, v.episode, v.episodeTitle, v.at)
                else -> g.show(v.title).seasons.let { m -> m[v.season] = maxOf(m[v.season] ?: 0L, v.at) }
            }
        }
        return g
    }
}

/** Film de l'historique importé : titre, date du dernier visionnage. */
class ImportFilm(val title: String, var at: Long)

/** Série de l'historique importé : épisodes vus, et saisons vues sans détail d'épisode (Prime Video). */
class ImportShow(val name: String) {
    val watches: MutableList<EpWatch> = ArrayList()

    /** Saison (null si inconnue) → date la plus récente, pour les lignes « season » de Prime Video. */
    val seasons: MutableMap<Int?, Long> = LinkedHashMap()

    val at: Long get() = maxOf(watches.maxOfOrNull { it.at } ?: 0L, seasons.values.maxOrNull() ?: 0L)

    /** Saisons vues sans aucun épisode connu (graines de recommandations seulement). */
    val seasonsOnly: Int get() = seasons.keys.count { s -> watches.none { it.season == s } }

    /** Poids de la graine de recommandation : un par épisode, environ deux par saison sans détail. */
    val weight: Int get() = watches.size + 2 * seasonsOnly
}

/** Historique regroupé par film / série (clé : [Trending.matchKey] du titre), dans l'ordre du fichier. */
class ImportGroups {
    val films = LinkedHashMap<String, ImportFilm>()
    val shows = LinkedHashMap<String, ImportShow>()

    fun film(title: String, at: Long) {
        films.getOrPut(Trending.matchKey(title)) { ImportFilm(title, at) }.let { if (at > it.at) it.at = at }
    }

    fun show(name: String): ImportShow = shows.getOrPut(Trending.matchKey(name)) { ImportShow(name) }
}

// =============================================================================== Titres (logique pure, testée)

/** Titre Netflix classé : film, ou épisode d'une série. */
sealed class NfTitle {
    data class Film(val title: String) : NfTitle()

    /**
     * Épisode. [label] : segment de saison tel quel (« Saison 2 », « Mini-série », « Stranger Things 4 »…),
     * null s'il n'y en a pas. [season] / [episode] : null quand inconnus (à déduire).
     */
    data class Ep(
        val series: String,
        val label: String?,
        val season: Int?,
        val episode: Int?,
        val episodeTitle: String?,
    ) : NfTitle()
}

/** Épisode du catalogue (saison, numéro, titre) vu par le rapprochement. */
data class EpRef(val season: Int, val number: Int, val title: String)

/** Un épisode vu sur Netflix, à placer (saison, numéro) dans le catalogue. */
data class EpWatch(val label: String?, val season: Int?, val episode: Int?, val title: String?, val at: Long)

object NetflixTitles {
    /** Séparateur Netflix « : » collé au mot précédent ; « Mission : Impossible » (espace avant) n'est pas coupé. */
    private val SEP = Regex("""(?<=\S): """)

    private val SPACES = Regex("[\\u00A0\\u2007\\u202F\\t]+")

    /** Espaces insécables (Netflix écrit « Saison 4 » avec U+00A0) ramenées à des espaces simples. */
    fun clean(s: String): String = SPACES.replace(s, " ").trim()

    fun segments(title: String): List<String> = clean(title).split(SEP).map { it.trim() }.filter { it.isNotEmpty() }

    private val WORDS = mapOf(
        "un" to 1, "une" to 1, "premier" to 1, "premiere" to 1, "deux" to 2, "trois" to 3, "quatre" to 4,
        "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11,
        "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16, "dix-sept" to 17,
        "dix-huit" to 18, "dix-neuf" to 19, "vingt" to 20,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    )
    private val ROMAN = Regex("^[ivxl]+$")

    /** « 4 », « quatre », « IV » → 4 ; null sinon. */
    fun number(word: String): Int? {
        val w = searchKey(word.trim().trimEnd('.', ',', ':'))
        w.toIntOrNull()?.let { return it.takeIf { n -> n in 0..999 } }
        WORDS[w]?.let { return it }
        if (ROMAN.matches(w)) {
            val v = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50)
            var total = 0
            for (i in w.indices) {
                val cur = v.getValue(w[i])
                total += if (i + 1 < w.length && v.getValue(w[i + 1]) > cur) -cur else cur
            }
            return total.takeIf { it in 1..60 }
        }
        return null
    }

    private val MINI = Regex("""^(mini[- ]?s[ée]ries?|miniseries|limited series|s[ée]rie limit[ée]e)$""", RegexOption.IGNORE_CASE)
    private val SEASON = Regex(
        """^(saison|season|partie|part|volume|vol\.?|livre|book|chapitre|chapter|temporada|staffel|collection|cycle)\s+(\S+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val SHORT_SEASON = Regex("""^s(\d{1,2})$""", RegexOption.IGNORE_CASE)
    private val EPISODE = Regex(
        """^(?:[ée]pisode|episode|[ée]p\.?|chapitre|chapter)\s+([^\s:.,]+)(?:\s*[:.,\-–].*)?$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Numéro de saison d'un segment « marqueur de saison » : Saison / Season / Partie / Part / Volume / Livre /
     * Chapitre N, Mini-série / Limited Series (= 1), « <nom de la série> N » (« Stranger Things 4 »). Null sinon.
     */
    fun seasonOf(label: String, series: String? = null): Int? {
        val l = clean(label)
        if (MINI.matches(l)) return 1
        SEASON.find(l)?.let { m -> number(m.groupValues[2])?.let { return it } }
        SHORT_SEASON.find(l)?.let { return it.groupValues[1].toInt() }
        if (series != null) {
            val key = searchKey(series).trim()
            val lk = searchKey(l)
            if (lk.startsWith("$key ")) number(lk.removePrefix("$key ").trim())?.let { return it }
        }
        return null
    }

    /** Numéro tiré d'un titre d'épisode « Épisode 4 », « Chapitre un : L'incursion »… ; null sinon. */
    fun episodeNumber(title: String): Int? = EPISODE.find(clean(title))?.let { number(it.groupValues[1]) }

    /** Premiers segments présents dans au moins 2 titres différents (« Recalé: Traqué », « Recalé: Collé »…). */
    fun repeatedPrefixes(titles: Collection<String>): Set<String> = titles.distinct()
        .mapNotNull { t -> segments(t).takeIf { it.size >= 2 }?.first()?.let { Trending.matchKey(it) } }
        .groupingBy { it }.eachCount().filterValues { it >= 2 }.keys

    private fun ep(series: String, rest: List<String>): NfTitle.Ep {
        val label = if (rest.size >= 2) rest[0] else null
        val epTitle = (if (rest.size >= 2) rest.drop(1) else rest).joinToString(": ")
        return NfTitle.Ep(series, label, label?.let { seasonOf(it, series) }, episodeNumber(epTitle), epTitle)
    }

    /**
     * Film ou épisode ? Épisode quand un segment (après le premier) est un marqueur de saison ; ou quand le
     * dernier segment est « Épisode N » / « Chapitre N » ; sinon quand le plus long préfixe est une série du
     * catalogue ([isSeries]) ; sinon quand le premier segment revient dans plusieurs titres ([repeated]) et
     * que le titre complet n'est pas un film du catalogue ([isMovie]). Sinon : film.
     */
    fun classify(
        title: String,
        isSeries: (String) -> Boolean = { false },
        isMovie: (String) -> Boolean = { false },
        repeated: Set<String> = emptySet(),
    ): NfTitle {
        val t = clean(title)
        val segs = segments(t)
        if (segs.size < 2) return NfTitle.Film(t)
        for (i in 1 until segs.size - 1) {
            val series = segs.take(i).joinToString(": ")
            val s = seasonOf(segs[i], series) ?: continue
            val epTitle = segs.drop(i + 1).joinToString(": ")
            return NfTitle.Ep(series, segs[i], s, episodeNumber(epTitle), epTitle)
        }
        val last = segs.last()
        episodeNumber(last)?.let { n ->
            return if (segs.size >= 3) NfTitle.Ep(segs[0], segs.subList(1, segs.size - 1).joinToString(": "), null, n, last)
            else NfTitle.Ep(segs[0], null, null, n, last)
        }
        for (i in segs.size - 1 downTo 1) {
            val prefix = segs.take(i).joinToString(": ")
            if (isSeries(prefix)) return ep(prefix, segs.drop(i))
        }
        if (Trending.matchKey(segs[0]) in repeated && !isMovie(t)) return ep(segs[0], segs.drop(1))
        return NfTitle.Film(t)
    }

    // ---- Rapprochement des épisodes

    /** Articles ignorés dans la comparaison des noms d'épisode (français et anglais). */
    private val ARTICLES = setOf("le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "the", "a", "an")

    /** Préfixe « Série - S03E04 - » d'un titre d'épisode du catalogue (après [searchKey]). */
    private val CODE_PREFIX = Regex("""^.*?\bs\d{1,3}\s*[ ._-]?\s*e\d{1,4}\b""")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /**
     * Nom d'épisode normalisé pour la comparaison : sans accents, ponctuation, casse ni articles, et sans le
     * préfixe « Série - S03E04 - » des titres du catalogue (« Académie Minerva - S01E08 - Le Bal » → « bal »).
     */
    fun titleKey(s: String): String {
        var k = searchKey(clean(s))
        CODE_PREFIX.find(k)?.let { k = k.substring(it.range.last + 1) }
        return k.replace(NON_ALNUM, " ").trim().split(' ').filter { it.isNotEmpty() && it !in ARTICLES }.joinToString(" ")
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * Ressemblance (0..1) de deux noms déjà normalisés ([titleKey]) : le meilleur de l'indice de Jaccard des mots,
     * du ratio de Levenshtein, et de l'inclusion d'un nom d'au moins 6 caractères dans l'autre (0,85).
     */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val wa = a.split(' ').toSet()
        val wb = b.split(' ').toSet()
        val jaccard = (wa intersect wb).size.toDouble() / (wa union wb).size
        val lev = 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
        val (short, long) = if (a.length <= b.length) a to b else b to a
        val incl = if (short.length >= 6 && " $long ".contains(" $short ")) 0.85 else 0.0
        return maxOf(jaccard, lev, incl)
    }

    /** Seuil de ressemblance d'un rapprochement approché. */
    const val FUZZY_MIN = 0.8

    /**
     * Rapprochement d'un nom d'épisode parmi des candidats (clé quelconque, nom) : nom identique une fois
     * normalisé (score 1), sinon le plus ressemblant si sa ressemblance atteint [FUZZY_MIN]. Null si rien, ou si
     * deux candidats différents sont à égalité.
     */
    fun <K> matchTitle(name: String, candidates: List<Pair<K, String>>, exactOnly: Boolean = false): Pair<K, Double>? {
        val n = titleKey(name)
        if (n.length < 2) return null
        val c = candidates.map { it.first to titleKey(it.second) }.filter { it.second.isNotEmpty() }
        val exact = c.filter { it.second == n }.map { it.first }.distinct()
        if (exact.size == 1) return exact[0] to 1.0
        if (exact.size > 1 || exactOnly) return null
        val scored = c.map { (k, t) -> k to similarity(n, t) }.filter { it.second >= FUZZY_MIN }
            .sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        if (scored.any { it.first != best.first && it.second >= best.second - 1e-9 }) return null
        return best
    }

    /**
     * Homonymes : index du candidat (score, année de sortie) au meilleur score ; à égalité, le plus récent sorti
     * au plus tard l'année [watchYear] du premier visionnage (sinon le premier de la liste). Null si liste vide.
     */
    fun pickHomonym(cands: List<Pair<Int, Int?>>, watchYear: Int?): Int? {
        if (cands.isEmpty()) return null
        val best = cands.maxOf { it.first }
        val top = cands.indices.filter { cands[it].first == best }
        return top.filter { i -> cands[i].second.let { y -> y != null && (watchYear == null || y <= watchYear) } }
            .maxByOrNull { cands[it].second!! } ?: top.first()
    }

    /** Numéro de l'épisode dont le nom ressemble à [name] (voir [matchTitle]) ; null si rien ou ambigu. */
    fun fuzzyEpisode(name: String, candidates: List<Pair<Int, String>>): Int? = matchTitle(name, candidates)?.first

    /**
     * Épisodes sans numéro d'une saison, placés dans l'ordre des visionnages (le plus ancien d'abord) : chacun
     * prend le premier numéro libre après le plus haut épisode déjà placé et vu avant lui ([mapped] : instant →
     * numéro), sinon le premier numéro libre.
     */
    fun fillByOrder(unknown: List<EpWatch>, free: List<Int>, mapped: List<Pair<Long, Int>> = emptyList()): Map<EpWatch, Int> {
        val left = free.distinct().sorted().toMutableList()
        val done = mapped.toMutableList()
        val out = LinkedHashMap<EpWatch, Int>()
        for (w in unknown.sortedBy { it.at }) {
            if (left.isEmpty()) break
            val prior = done.filter { it.first <= w.at }.maxOfOrNull { it.second } ?: 0
            val n = left.firstOrNull { it > prior } ?: left.first()
            left.remove(n)
            done += w.at to n
            out[w] = n
        }
        return out
    }

    /**
     * Place chaque épisode vu (saison, numéro) parmi les épisodes du catalogue [catalog].
     *
     * Saison : marqueur du titre ; sans marqueur (« Série: Titre »), le nom de l'épisode est cherché dans TOUTES
     * les saisons, sinon la saison où les autres épisodes ont été reconnus, sinon la seule / la première saison.
     * Numéro : « Épisode N » ; sinon le nom de l'épisode comparé aux titres du catalogue (préfixe « Série -
     * S01E02 - » ignoré) et aux noms TMDB en français ([names]) puis en anglais ([namesEn]) : nom identique,
     * puis ressemblant ([FUZZY_MIN]) ; sinon l'ordre des visionnages ([fillByOrder]).
     * Saison complète : autant d'épisodes vus (distincts) que d'épisodes dans la saison ([seasonCount] TMDB, sinon
     * le catalogue) → tous ses épisodes du catalogue sont marqués vus, même ceux qu'aucun titre n'a désignés
     * (entrées ajoutées, sans titre, datées du premier visionnage de la saison).
     * Un épisode qu'on ne sait pas placer est absent du résultat.
     */
    fun mapEpisodes(
        watches: List<EpWatch>,
        catalog: List<EpRef>,
        names: (Int) -> List<Pair<Int, String>> = { emptyList() },
        tmdbSeasons: () -> List<Int> = { emptyList() },
        namesEn: (Int) -> List<Pair<Int, String>> = { emptyList() },
        seasonCount: (Int) -> Int? = { null },
    ): Map<EpWatch, Pair<Int, Int>> {
        val out = LinkedHashMap<EpWatch, Pair<Int, Int>>()
        if (catalog.isEmpty()) return out
        val bySeason = catalog.groupBy { it.season }
        val catSeasons = bySeason.keys.sorted()
        fun exists(s: Int, n: Int) = bySeason[s].orEmpty().any { it.number == n }

        /** Nom d'épisode cherché dans [seasons] : identique (catalogue, FR, EN), puis ressemblant. */
        fun byTitle(t: String, seasons: List<Int>): Pair<Int, Int>? {
            val cat = seasons.flatMap { s -> bySeason[s].orEmpty().map { (s to it.number) to it.title } }
            fun tmdb(src: (Int) -> List<Pair<Int, String>>) =
                seasons.flatMap { s -> src(s).filter { exists(s, it.first) }.map { (s to it.first) to it.second } }
            val fr = tmdb(names)
            matchTitle(t, cat + fr, exactOnly = true)?.let { return it.first }
            val en = tmdb(namesEn)
            matchTitle(t, en, exactOnly = true)?.let { return it.first }
            return matchTitle(t, cat + fr + en)?.first
        }

        val placed = ArrayList<Pair<EpWatch, Int>>() // épisode, saison
        val noSeason = ArrayList<EpWatch>()
        for (w in watches) {
            val s = w.season ?: if (w.label == null && catSeasons.size == 1) catSeasons[0] else null
            if (s != null) { placed += w to s; continue }
            // Saison inconnue : d'après le nom de l'épisode, dans toutes les saisons.
            val hit = if (w.title != null && w.episode == null) {
                byTitle(w.title, (catSeasons + tmdbSeasons()).distinct().filter { it in bySeason })
            } else null
            when {
                hit != null -> { out[w] = hit; placed += w to hit.first }
                w.label == null -> noSeason += w
            }
        }
        if (noSeason.isNotEmpty()) {
            // La saison où les autres épisodes ont été reconnus (la plus fréquente, puis la plus récente).
            val s = out.values.groupingBy { it.first }.eachCount().maxWithOrNull(compareBy({ it.value }, { it.key }))?.key
                ?: catSeasons[0]
            noSeason.forEach { placed += it to s }
        }
        for ((s, list) in placed.groupBy({ it.second }, { it.first })) {
            val unknown = ArrayList<EpWatch>()
            for (w in list) {
                if (out.containsKey(w)) continue
                var n = w.episode?.takeIf { exists(s, it) }
                if (n == null && w.episode == null && w.title != null) n = byTitle(w.title, listOf(s))?.second
                if (n != null) out[w] = s to n else if (w.episode == null) unknown += w
            }
            val mapped = list.mapNotNull { w -> out[w]?.let { w.at to it.second } }
            if (unknown.isNotEmpty()) {
                val used = mapped.map { it.second }.toSet()
                val free = bySeason[s].orEmpty().map { it.number }.filter { it !in used }
                fillByOrder(unknown, free, mapped).forEach { (w, n) -> out[w] = s to n }
            }
            // Saison complète d'après le nombre d'épisodes vus.
            val total = seasonCount(s)?.takeIf { it > 0 } ?: bySeason[s].orEmpty().map { it.number }.distinct().size
            if (total > 0 && list.size >= total) {
                val have = out.filterKeys { it in list }.values.map { it.second }.toSet()
                val first = list.minOf { it.at }
                for (n in bySeason[s].orEmpty().map { it.number }.distinct().sorted()) {
                    if (n !in have) out[EpWatch(list[0].label, s, n, null, first)] = s to n
                }
            }
        }
        return out
    }
}

// =============================================================================== Graines de recommandation

/**
 * Titre importé connu de TMDB ([tmdb] = "movie:<id>" / "tv:<id>") : graine pour [Reco] (titre absent du catalogue,
 * ou série vue sur Prime Video sans détail d'épisode). [source] : [Progress.NETFLIX] ou [Progress.PRIME].
 */
data class NfSeed(val tmdb: String, val title: String, val at: Long, val count: Int = 1, val source: String = Progress.NETFLIX)

/** Graines de l'historique importé : un fichier par profil (Netflix et Prime Video mêlés, chaque graine marquée). */
object NetflixSeeds {
    fun file(ctx: Context, profile: String) = File(
        File(ctx.filesDir, "reco").also { it.mkdirs() },
        "netflix_seeds_" + profile.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json",
    )

    fun encode(list: List<NfSeed>): String =
        MiniJson.write(list.map { mapOf("t" to it.tmdb, "n" to it.title, "at" to it.at, "c" to it.count, "s" to it.source) })

    fun decode(text: String): List<NfSeed> = runCatching {
        (MiniJson.parse(text) as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val t = m["t"] as? String ?: return@mapNotNull null
            if (!t.startsWith("movie:") && !t.startsWith("tv:")) return@mapNotNull null
            NfSeed(
                t, m["n"] as? String ?: "", (m["at"] as? Number)?.toLong() ?: 0L, (m["c"] as? Number)?.toInt() ?: 1,
                (m["s"] as? String)?.takeIf { it.isNotBlank() } ?: Progress.NETFLIX,
            )
        }
    }.getOrDefault(emptyList())

    /** Fusion avec les graines déjà enregistrées : une par titre TMDB, la plus récente (réimport sans doublon). */
    fun merge(old: List<NfSeed>, new: List<NfSeed>): List<NfSeed> {
        val out = LinkedHashMap<String, NfSeed>()
        for (s in old + new) {
            val p = out[s.tmdb]
            out[s.tmdb] = if (p == null) s else if (s.at > p.at || (s.at == p.at && s.count > p.count)) s else p
        }
        return out.values.sortedByDescending { it.at }
    }

    fun read(ctx: Context, profile: String): List<NfSeed> =
        file(ctx, profile).takeIf { it.exists() }?.let { f -> runCatching { decode(f.readText()) }.getOrNull() }.orEmpty()

    fun write(ctx: Context, profile: String, list: List<NfSeed>) {
        runCatching {
            val f = file(ctx, profile)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(encode(list))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** « Annuler les imports » : graines du profil oubliées (Netflix et Prime Video). */
    fun delete(ctx: Context, profile: String) {
        runCatching {
            val f = file(ctx, profile)
            f.delete()
            File(f.parentFile, f.name + ".tmp").delete()
        }
    }

    /** Change quand les graines du profil changent (signature des recommandations). */
    fun stamp(ctx: Context, profile: String): String =
        file(ctx, profile).let { if (it.exists()) "nf:${it.lastModified()}:${it.length()}" else "nf:0" }

    /**
     * Graines Netflix au format [RecoSeed] (clé "tmdb:<type>:<id>"), pondérées comme un titre fini :
     * film 1, série 0,5 + 0,1 par épisode (1 au plus), × récence (demi-vie 30 jours).
     */
    fun recoSeeds(list: List<NfSeed>, now: Long): List<RecoSeed> = list.map { s ->
        val type = s.tmdb.substringBefore(':')
        val completion = if (type == Tmdb.MOVIE) 1.0 else min(1.0, 0.5 + 0.1 * s.count)
        RecoSeed("tmdb:" + s.tmdb, s.title, type, completion * RecoEngine.decay(now - s.at), s.at)
    }
}

// =============================================================================== Dernier import (par profil)

/**
 * Dernier import d'un profil pour une origine ([source] : Netflix ou Prime Video) : date, nom du fichier,
 * films / épisodes reconnus, graines, lignes écrites (celles qui ne remplaçaient rien de plus récent).
 */
data class NfImportMeta(
    val at: Long,
    val fileName: String,
    val films: Int,
    val episodes: Int,
    val seeds: Int,
    val written: Int,
    val source: String = Progress.NETFLIX,
)

object NetflixImportMeta {
    private const val PREFS = "netflix_import"

    fun encode(m: NfImportMeta): String = MiniJson.write(
        mapOf(
            "at" to m.at, "file" to m.fileName, "films" to m.films, "episodes" to m.episodes, "seeds" to m.seeds,
            "written" to m.written, "src" to m.source,
        )
    )

    fun decode(text: String): NfImportMeta? = runCatching {
        val o = MiniJson.parse(text) as Map<*, *>
        fun i(k: String) = (o[k] as? Number)?.toInt() ?: 0
        NfImportMeta(
            at = (o["at"] as? Number)?.toLong() ?: return null, fileName = o["file"] as? String ?: "",
            films = i("films"), episodes = i("episodes"), seeds = i("seeds"), written = i("written"),
            source = (o["src"] as? String)?.takeIf { it.isNotBlank() } ?: Progress.NETFLIX,
        )
    }.getOrNull()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Clé : le profil seul pour Netflix (format des versions précédentes), "<profil>|<origine>" sinon. */
    fun key(profile: String, source: String) = if (source == Progress.NETFLIX) profile else "$profile|$source"

    fun read(ctx: Context, profile: String, source: String = Progress.NETFLIX): NfImportMeta? =
        prefs(ctx).getString(key(profile, source), null)?.let(::decode)

    /** Derniers imports du profil, toutes origines, le plus récent d'abord. */
    fun readAll(ctx: Context, profile: String): List<NfImportMeta> =
        Progress.IMPORTED_SOURCES.mapNotNull { read(ctx, profile, it) }.sortedByDescending { it.at }

    fun write(ctx: Context, profile: String, m: NfImportMeta) {
        prefs(ctx).edit().putString(key(profile, m.source), encode(m)).apply()
    }

    fun clear(ctx: Context, profile: String) {
        prefs(ctx).edit().apply { Progress.IMPORTED_SOURCES.forEach { remove(key(profile, it)) } }.apply()
    }
}

/** Nom affiché d'une origine d'import. */
fun importSourceName(source: String?): String = if (source == Progress.PRIME) "Prime Video" else "Netflix"

// =============================================================================== Import (réseau, base, état)

/**
 * « Importer un historique (Netflix ou Prime Video) » : rapproche l'activité de visionnage Netflix, ou le fichier
 * du bouton « Exporter Prime Video » (reconnu à son en-tête), du catalogue du profil ; marque films et épisodes
 * comme vus (lignes [Progress.NETFLIX] / [Progress.PRIME]), garde les titres absents du catalogue comme graines
 * de recommandations. Un seul import à la fois ; état observable ([state]) pour la TV et la page du téléphone.
 */
internal object NetflixImport {
    const val MAX_BYTES = 5L * 1024 * 1024
    const val MAX_TMDB_CALLS = 300
    private const val CONCURRENCY = 4
    private const val MOVIE_MS = 2L * 3600 * 1000
    private const val EPISODE_MS = 45L * 60 * 1000

    data class Summary(
        val profileName: String,
        val filmsFound: Int,
        val filmsTotal: Int,
        val seriesFound: Int,
        val seriesTotal: Int,
        val episodes: Int,
        val inContinue: Int,
        val notFound: List<String>,
        val notFoundCount: Int,
        val profileId: String = "",
        val source: String = Progress.NETFLIX,
        /** Saisons vues sur Prime Video sans détail d'épisode (graines de recommandations seulement). */
        val seasonsOnly: Int = 0,
    )

    data class State(
        val running: Boolean = false,
        val phase: String = "",
        val done: Int = 0,
        val total: Int = 0,
        val summary: Summary? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lignes de résumé (affichées sur la TV et sur la page du téléphone). */
    fun summaryLines(s: Summary): List<String> = listOfNotNull(
        "Films reconnus : ${s.filmsFound}/${s.filmsTotal}",
        "Séries reconnues : ${s.seriesFound}/${s.seriesTotal}",
        "Épisodes marqués vus : ${s.episodes}",
        "Séries ajoutées à « Continuer à regarder » : ${s.inContinue}",
        if (s.seasonsOnly > 0) "Saisons sans détail d'épisode (recommandations seulement) : ${s.seasonsOnly}" else null,
    )

    /** « Import Prime Video terminé pour « Léa » ». */
    fun doneLine(s: Summary): String = "Import ${importSourceName(s.source)} terminé pour « ${s.profileName} »"

    /** État au format JSON, pour la page du téléphone (sondée pendant l'import). */
    fun statusJson(): String {
        val s = _state.value
        return MiniJson.write(
            mapOf(
                "running" to s.running, "phase" to s.phase, "done" to s.done, "total" to s.total, "error" to s.error,
                "summary" to s.summary?.let { r ->
                    mapOf(
                        "profile" to r.profileName, "source" to importSourceName(r.source), "done" to doneLine(r),
                        "lines" to summaryLines(r),
                        "notFound" to r.notFound, "notFoundCount" to r.notFoundCount,
                    )
                },
            )
        )
    }

    // ---------------------------------------------------------------- Import en place : état, annulation

    /**
     * Imports présents sur un profil : lignes importées encore là par origine ([counts]), graines, dernier import
     * (s'il est connu, toutes origines confondues).
     */
    data class Info(
        val rows: Int,
        val hasSeeds: Boolean,
        val last: NfImportMeta?,
        val counts: Map<String, Int> = emptyMap(),
    ) {
        /** « Annuler les imports » n'est proposé que s'il reste quelque chose à retirer. */
        val undoable get() = rows > 0 || hasSeeds

        /** « 1 022 titres importés (Netflix 1 000 · Prime Video 22) ». */
        val label: String get() = titlesLabel(rows) + sourcesLabel(counts)
    }

    fun info(ctx: Context, profileId: String): Info {
        val counts = Progress.IMPORTED_SOURCES.associateWith { src ->
            runCatching { ProgressStore.get(ctx).countImported(profileId, src) }.getOrDefault(0)
        }
        return Info(
            rows = counts.values.sum(),
            hasSeeds = NetflixSeeds.file(ctx, profileId).exists(),
            last = NetflixImportMeta.readAll(ctx, profileId).firstOrNull(),
            counts = counts,
        )
    }

    /** « 1 022 titres importés » (espace fine insécable entre les milliers). */
    fun titlesLabel(n: Int): String = "${groupDigits(n)} ${if (n > 1) "titres importés" else "titre importé"}"

    /** Détail par origine, seulement si plusieurs origines ont des titres : « (Netflix 10 · Prime Video 2) ». */
    fun sourcesLabel(counts: Map<String, Int>): String {
        val l = counts.filterValues { it > 0 }
        if (l.size < 2) return ""
        return l.entries.sortedBy { it.key != Progress.NETFLIX }
            .joinToString(" · ", " (", ")") { "${importSourceName(it.key)} ${groupDigits(it.value)}" }
    }

    fun groupDigits(n: Int): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()

    /** Profils ayant un import Netflix ou Prime Video (pour la page du téléphone), en JSON. */
    fun importsJson(ctx: Context): String = MiniJson.write(
        Profiles.list.value.mapNotNull { p ->
            val i = info(ctx, p.id)
            if (!i.undoable) return@mapNotNull null
            mapOf(
                "profile" to p.id, "name" to p.name, "rows" to i.rows, "label" to i.label,
                "at" to i.last?.at, "file" to i.last?.fileName,
            )
        }
    )

    /**
     * « Annuler les imports » de [profileId] : supprime les films et épisodes importés de Netflix et de Prime Video
     * (jamais l'historique Prisme ; suppressions envoyées aux autres TV), les graines de recommandations et les
     * résumés des derniers imports, puis recalcule les recommandations. Renvoie le nombre de titres retirés, ou
     * -1 si un import tourne.
     */
    fun undo(ctx: Context, profileId: String): Int {
        val app = ctx.applicationContext
        synchronized(this) {
            if (_state.value.running) return -1
            val removed = ProgressStore.get(app).removeImported(profileId, Progress.IMPORTED_SOURCES)
            NetflixSeeds.delete(app, profileId)
            NetflixImportMeta.clear(app, profileId)
            if (_state.value.summary?.profileId == profileId) _state.value = State()
            Reco.invalidate(app, profileId)
            ProgressStore.notifyChanged()
            runCatching { DriveSync.requestSync(app, profileId, minIntervalMs = 0, delayMs = 2_000) }
            val ready = CatalogRepository.state.value as? CatalogRepository.State.Ready
            if (ready != null && Profiles.current.value == profileId) {
                scope.launch { runCatching { Reco.rows(app, ready.catalog) } }
            }
            return removed
        }
    }

    /** Démarre l'import de [csv] pour [profileId] ; renvoie un message d'erreur, null si l'import est lancé. */
    fun start(ctx: Context, profileId: String, csv: String, fileName: String = ""): String? {
        val app = ctx.applicationContext
        synchronized(this) {
            if (_state.value.running) return "Un import est déjà en cours sur la TV."
            val profile = Profiles.get(profileId) ?: return "Profil inconnu."
            val tz = TimeZone.getDefault()
            val prime = PrimeCsv.isPrime(csv)
            val primeViews = if (prime) runCatching { PrimeCsv.parse(csv, tz) }.getOrDefault(emptyList()) else emptyList()
            val views = if (prime) emptyList() else runCatching { NetflixCsv.parse(csv, tz) }.getOrDefault(emptyList())
            val count = if (prime) primeViews.size else views.size
            if (count == 0) {
                return "Aucun titre trouvé : choisis le fichier NetflixViewingHistory.csv ou PrimeVideoViewingHistory.csv."
            }
            val ready = CatalogRepository.state.value as? CatalogRepository.State.Ready
                ?: return "Le catalogue n'est pas encore chargé sur la TV."
            val full = ready.catalog.unfiltered
            val catalog = if (profile.kids) full.kidsOnly() else full
            val source = if (prime) Progress.PRIME else Progress.NETFLIX
            _state.value = State(running = true, phase = "Analyse de $count titres ${importSourceName(source)}…")
            scope.launch {
                try {
                    val summary = run(app, profile, views, primeViews, catalog, fileName)
                    _state.value = State(running = false, phase = "Import terminé", summary = summary)
                    // Recommandations du profil affiché recalculées, synchro vers les autres TV.
                    runCatching { DriveSync.requestSync(app, profileId, minIntervalMs = 0, delayMs = 2_000) }
                    if (Profiles.current.value == profileId) runCatching { Reco.rows(app, ready.catalog) }
                } catch (e: Exception) {
                    _state.value = State(running = false, error = "Import interrompu : ${e.message ?: e.javaClass.simpleName}")
                }
            }
            return null
        }
    }

    private fun progress(phase: String, done: Int, total: Int) {
        _state.value = _state.value.copy(phase = phase, done = done, total = total)
    }

    /** Netflix : chaque titre classé film / épisode (analyse du titre, aidée par le catalogue), puis regroupé. */
    private fun groupNetflix(views: List<NfView>, movies: CatalogIndex<Movie>, seriesIdx: CatalogIndex<Series>): ImportGroups {
        val seriesMemo = HashMap<String, Boolean>()
        val movieMemo = HashMap<String, Boolean>()
        val repeated = NetflixTitles.repeatedPrefixes(views.map { it.title })
        val g = ImportGroups()
        for (v in views) {
            val c = NetflixTitles.classify(
                v.title,
                isSeries = { t -> seriesMemo.getOrPut(t) { seriesIdx.find(t, null, null) != null } },
                isMovie = { t -> movieMemo.getOrPut(t) { movies.find(t, null, null) != null } },
                repeated = repeated,
            )
            when (c) {
                is NfTitle.Film -> g.film(c.title, v.at)
                is NfTitle.Ep -> g.show(c.series).watches += EpWatch(c.label, c.season, c.episode, c.episodeTitle, v.at)
            }
        }
        return g
    }

    private suspend fun run(
        ctx: Context,
        profile: Profile,
        views: List<NfView>,
        primeViews: List<PvView>,
        catalog: Catalog,
        fileName: String,
    ): Summary {
        VodMeta.load(ctx)
        val movies = CatalogIndex(catalog.movies, { it.name }, { VodMeta.get(VodMeta.movieKey(it.id)) }, { it.quality })
        val seriesIdx = CatalogIndex(catalog.series, { it.name }, { VodMeta.get(VodMeta.seriesKey(it.id)) }, { it.quality })
        val prime = primeViews.isNotEmpty()
        val src = if (prime) Progress.PRIME else Progress.NETFLIX

        // ---- Classement film / épisode, regroupement.
        val groups = if (prime) PrimeCsv.group(primeViews) else groupNetflix(views, movies, seriesIdx)
        val films = groups.films
        val shows = groups.shows
        // Un même épisode revu : sa date la plus récente.
        for (s in shows.values) {
            val latest = s.watches.groupBy { Triple(it.label, it.season, it.episodeKey()) }.values.map { l -> l.maxBy { it.at } }
            s.watches.clear(); s.watches += latest
        }

        val session = Tmdb.Session(ctx, refreshStale = false, maxRequests = MAX_TMDB_CALLS)
        val settings = Settings(ctx)
        val rows = ConcurrentHashMap<String, Progress>()
        val seeds = ConcurrentHashMap<String, NfSeed>()
        val notFound = ConcurrentHashMap<String, Long>()
        val filmsFound = AtomicInteger()
        val seriesFound = AtomicInteger()
        val seriesIds = ConcurrentHashMap<String, Long>()
        val done = AtomicInteger()
        val total = films.size + shows.size
        val sem = Semaphore(CONCURRENCY)
        val ctxImport = Ctx(profile.id, session, src, prime, seeds, notFound) { p -> rows.merge(p.key, p) { a, b -> if (b.updated > a.updated) b else a } }
        progress("Recherche des titres dans le catalogue…", 0, total)

        // Le plus récent d'abord : le budget TMDB va en priorité à ce qu'on vient de regarder.
        val jobs: List<suspend () -> Unit> =
            (films.values.map { f -> f.at to suspend { importFilm(ctxImport, movies, f, filmsFound) } } +
                shows.values.map { s ->
                    s.at to suspend {
                        importShow(ctx, ctxImport, settings, seriesIdx, catalog, s, seriesFound, seriesIds)
                    }
                }).sortedByDescending { it.first }.map { it.second }
        coroutineScope {
            jobs.map { job ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        runCatching { job() }
                        progress("Recherche des titres dans le catalogue…", done.incrementAndGet(), total)
                    }
                }
            }.awaitAll()
        }

        // ---- Écriture.
        progress("Enregistrement…", total, total)
        val store = ProgressStore.get(ctx)
        // Réimport : les lignes importées (même origine) des séries rapprochées sont remplacées par la nouvelle
        // correspondance (une erreur de saison / numéro d'un import précédent est corrigée).
        val written = store.importRows(profile.id, rows.values.sortedBy { it.updated }, replaceSeries = seriesIds.keys, source = src)
        if (seeds.isNotEmpty()) {
            NetflixSeeds.write(ctx, profile.id, NetflixSeeds.merge(NetflixSeeds.read(ctx, profile.id), seeds.values.toList()))
        }
        ProgressStore.notifyChanged()
        val now = System.currentTimeMillis()
        NetflixImportMeta.write(
            ctx, profile.id,
            NfImportMeta(
                at = now, fileName = fileName, films = rows.values.count { it.type == Progress.MOVIE },
                episodes = rows.values.count { it.type == Progress.EPISODE }, seeds = seeds.size, written = written,
                source = src,
            ),
        )
        val inContinue = seriesIds.keys.count { sid ->
            store.forSeriesOf(profile.id, sid).firstOrNull()
                ?.let {
                    it.source == src && now - it.updated <= ImportRules.IMPORT_MAX_AGE_MS && !store.isSeriesFinished(profile.id, it)
                } == true
        }
        val missing = notFound.entries.sortedByDescending { it.value }.map { it.key }
        return Summary(
            profileName = profile.name,
            filmsFound = filmsFound.get(), filmsTotal = films.size,
            seriesFound = seriesFound.get(), seriesTotal = shows.size,
            episodes = rows.values.count { it.type == Progress.EPISODE },
            inContinue = inContinue,
            notFound = missing.take(20), notFoundCount = missing.size,
            profileId = profile.id,
            source = src,
            seasonsOnly = shows.values.sumOf { it.seasonsOnly },
        )
    }

    /** Contexte commun d'un import : session TMDB, origine des lignes, graines et titres non trouvés. */
    private class Ctx(
        val profileId: String,
        val session: Tmdb.Session,
        val source: String,
        /** Titres Prime Video : souvent en anglais, recherche TMDB aussi en en-US. */
        val english: Boolean,
        val seeds: MutableMap<String, NfSeed>,
        val notFound: MutableMap<String, Long>,
        val put: (Progress) -> Unit,
    ) {
        fun seed(tmdb: String, title: String, at: Long, count: Int = 1) {
            seeds[tmdb] = NfSeed(tmdb, title, at, count, source)
        }
    }

    private fun EpWatch.episodeKey(): String = episode?.toString() ?: (title?.let { searchKey(it) } ?: "")

    /**
     * Recherche TMDB (/search/movie ou /search/tv, en français, cache disque 7 jours) : titre identique (traduit
     * ou original) en priorité. [english] : sans titre identique, nouvelle recherche en en-US (titres anglais de
     * Prime Video) ; le résultat garde le titre français quand TMDB l'a renvoyé dans la première liste.
     */
    private fun search(
        session: Tmdb.Session,
        type: String,
        query: String,
        english: Boolean = false,
        choose: ((List<TmdbHit>) -> TmdbHit?)? = null,
    ): TmdbHit? {
        val q = CatalogTitles.cleanTitle(query).ifBlank { query }
        val hits = TmdbParse.hits(Tmdb.get(session, "/search/$type", "query" to q), type)
        val k = Trending.matchKey(q)
        fun exact(h: TmdbHit) = Trending.matchKey(h.title) == k || h.original?.let(Trending::matchKey) == k
        // Homonymes (« A Different World » 1987 et 2026) : départagés par [choose].
        val same = hits.take(5).filter(::exact)
        if (same.size > 1 && choose != null) choose(same.take(3))?.let { return it }
        same.firstOrNull()?.let { return it }
        if (english) {
            val en = TmdbParse.hits(Tmdb.get(session, "/search/$type", "query" to q, "language" to "en-US"), type)
            en.take(5).firstOrNull(::exact)?.let { e -> return hits.firstOrNull { it.id == e.id } ?: e }
        }
        return hits.firstOrNull()
    }

    /** Titres à comparer au catalogue pour un résultat TMDB : titre original, et le titre du fichier. */
    private fun altTitles(h: TmdbHit, fileTitle: String) = listOfNotNull(h.original, fileTitle).distinct()

    private fun importFilm(c: Ctx, movies: CatalogIndex<Movie>, f: ImportFilm, found: AtomicInteger) {
        var hit: TmdbHit? = null
        val movie = movies.find(f.title, null, null) ?: search(c.session, Tmdb.MOVIE, f.title, c.english)?.let { h ->
            hit = h
            movies.find(h.title, h.year, h.id, altTitles(h, f.title))
        }
        if (movie == null) {
            c.notFound[f.title] = f.at
            hit?.let { h -> c.seed("movie:${h.id}", h.title, f.at) }
            return
        }
        found.incrementAndGet()
        c.put(
            Progress(
                key = Progress.movieKey(movie.id), type = Progress.MOVIE, itemId = movie.id, title = movie.name,
                subtitle = null, poster = movie.poster, url = movie.url, position = MOVIE_MS, duration = MOVIE_MS,
                updated = f.at, seriesId = null, season = 0, episode = 0, source = c.source,
            )
        )
    }

    private fun importShow(
        ctx: Context,
        c: Ctx,
        settings: Settings,
        index: CatalogIndex<Series>,
        catalog: Catalog,
        s: ImportShow,
        found: AtomicInteger,
        seriesIds: MutableMap<String, Long>,
    ) {
        val session = c.session
        var tmdbId: String? = null
        val choose = { cands: List<TmdbHit> -> chooseShow(session, cands, s) }
        // Plusieurs séries du catalogue sous ce nom (homonymes, ou versions) : identifiée d'abord par TMDB.
        val guided = if (index.count(s.name) > 1) search(session, Tmdb.TV, s.name, c.english, choose) else null
        val series = guided?.let { h -> tmdbId = h.id; index.find(h.title, h.year, h.id, altTitles(h, s.name)) }
            ?: (if (guided == null) index.find(s.name, null, null) else null)
            ?: (guided ?: search(session, Tmdb.TV, s.name, c.english, choose))?.let { h ->
                tmdbId = h.id
                index.find(h.title, h.year, h.id, altTitles(h, s.name)).also {
                    if (it == null) c.seed("tv:${h.id}", h.title, s.at, s.weight)
                }
            }
        if (series == null) {
            c.notFound[s.name] = s.at
            return
        }
        found.incrementAndGet()
        // Identifiant TMDB de la série, cherché seulement si nécessaire (nom d'épisode, saison sans détail).
        val tmdb by lazy {
            tmdbId ?: VodMeta.get(VodMeta.seriesKey(series.id))?.tmdbId?.trim()?.takeIf { (it.toLongOrNull() ?: 0) > 0 }
                ?: Tmdb.resolvedId(ctx, VodMeta.seriesKey(series.id))
                ?: search(session, Tmdb.TV, s.name, c.english)?.id
        }
        // Saisons vues sans détail (Prime Video) : aucune ligne d'épisode inventée, seulement une graine de
        // recommandation (environ deux épisodes par saison) ; rien dans « Continuer à regarder ».
        if (s.seasonsOnly > 0) {
            tmdb?.let { id ->
                val sid = s.seasons.filterKeys { k -> s.watches.none { it.season == k } }.values.maxOrNull() ?: s.at
                c.seed("tv:$id", CatalogTitles.cleanTitle(series.name).ifBlank { s.name }, sid, 2 * s.seasonsOnly)
            }
        }
        if (s.watches.isEmpty()) return
        val eps = series.episodes?.takeIf { it.isNotEmpty() }
            ?: catalog.seriesById[series.id]?.episodes?.takeIf { it.isNotEmpty() }
            ?: if (series.id.startsWith("m3u:") || settings.server.isBlank()) emptyList()
            else runCatching { XtreamClient(settings.server, settings.user, settings.pass).seriesInfo(series.id).episodes }
                .getOrDefault(emptyList())
        if (eps.isEmpty()) return

        val fr = HashMap<Int, List<Pair<Int, String>>>()
        val en = HashMap<Int, List<Pair<Int, String>>>()
        fun names(n: Int) = fr.getOrPut(n) { tmdb?.let { seasonNames(session, it, n, null) }.orEmpty() }
        fun namesEn(n: Int) = en.getOrPut(n) { tmdb?.let { seasonNames(session, it, n, "en-US") }.orEmpty() }
        // Saisons TMDB (numéro → nombre d'épisodes).
        val tmdbSeasons by lazy { tmdb?.let { tvSeasons(session, it) }.orEmpty() }
        val refs = eps.map { EpRef(it.season, it.number, it.title) }
        val placed = NetflixTitles.mapEpisodes(
            s.watches, refs, ::names, { tmdbSeasons.keys.take(10) }, ::namesEn,
            // Nombre d'épisodes TMDB seulement s'il y a des épisodes sans numéro (sinon : celui du catalogue).
            seasonCount = { n -> if (s.watches.any { it.episode == null }) tmdbSeasons[n] else null },
        )
        var any = false
        for ((w, sn) in placed) {
            val e = eps.firstOrNull { it.season == sn.first && it.number == sn.second } ?: continue
            val dur = if (e.durationSec > 0) e.durationSec * 1000 else EPISODE_MS
            any = true
            c.put(
                Progress(
                    key = Progress.episodeKey(e.id), type = Progress.EPISODE, itemId = e.id, title = series.name,
                    subtitle = "S%02d E%02d".format(e.season, e.number), poster = series.poster, url = e.url,
                    position = dur, duration = dur, updated = w.at, seriesId = series.id,
                    season = e.season, episode = e.number, source = c.source,
                )
            )
        }
        if (any) {
            seriesIds[series.id] = s.at
            // Dernier épisode connu du catalogue : une série vue jusqu'au bout est « terminée » (hors de
            // « Continuer à regarder ») tant qu'aucun nouvel épisode n'arrive ([NewEpisodes]).
            runCatching { NewEpisodes.markSeen(ctx, c.profileId, series, eps) }
        }
    }

    /** Noms des épisodes d'une saison TMDB ([lang] : null = français), (numéro, nom). */
    private fun seasonNames(session: Tmdb.Session, id: String, n: Int, lang: String?): List<Pair<Int, String>> {
        val json = if (lang == null) Tmdb.get(session, "/tv/$id/season/$n") else Tmdb.get(session, "/tv/$id/season/$n", "language" to lang)
        val o = runCatching { MiniJson.parse(json ?: "{}") as? Map<*, *> }.getOrNull()
        return (o?.get("episodes") as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val num = (m["episode_number"] as? Number)?.toInt() ?: return@mapNotNull null
            num to (m["name"] as? String ?: return@mapNotNull null)
        }
    }

    /** Saisons d'une série TMDB (hors épisodes spéciaux) : numéro → nombre d'épisodes, dans l'ordre. */
    private fun tvSeasons(session: Tmdb.Session, id: String): Map<Int, Int> {
        val o = runCatching {
            MiniJson.parse(Tmdb.get(session, "/tv/$id", "append_to_response" to "credits,keywords") ?: "{}") as? Map<*, *>
        }.getOrNull()
        val out = LinkedHashMap<Int, Int>()
        (o?.get("seasons") as? List<*>).orEmpty().forEach { e ->
            val m = e as? Map<*, *> ?: return@forEach
            val n = (m["season_number"] as? Number)?.toInt() ?: return@forEach
            if (n > 0) out[n] = (m["episode_count"] as? Number)?.toInt() ?: 0
        }
        return out
    }

    /**
     * Homonymes TMDB (même titre) : celui dont les noms d'épisodes (FR puis EN, 3 premières saisons) recouvrent
     * le mieux les épisodes vus ; à égalité, le plus récent sorti avant le premier visionnage.
     */
    private fun chooseShow(session: Tmdb.Session, cands: List<TmdbHit>, s: ImportShow): TmdbHit? {
        val titles = s.watches.filter { it.episode == null }.mapNotNull { it.title }.distinct()
        val first = s.watches.minOfOrNull { it.at } ?: s.at
        val year = GregorianCalendar().apply { timeInMillis = first }.get(java.util.Calendar.YEAR)
        val scored = cands.map { h ->
            val score = if (titles.isEmpty()) 0 else {
                val seasons = tvSeasons(session, h.id).keys.take(3)
                fun count(lang: String?): Int {
                    val all = seasons.flatMap { n -> seasonNames(session, h.id, n, lang) }
                    return titles.count { t -> NetflixTitles.matchTitle(t, all) != null }
                }
                count(null).takeIf { it > 0 } ?: count("en-US")
            }
            Triple(h, score, h.year)
        }
        return NetflixTitles.pickHomonym(scored.map { it.second to it.third }, year)?.let { scored[it].first }
    }
}
