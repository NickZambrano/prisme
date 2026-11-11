package com.fluxfr.tv.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Top 10 en France" : classements publics d'AlloCiné (box-office France pour les films,
 * séries populaires du moment), puis on retrouve chaque titre dans le catalogue IPTV.
 * Mis en cache une journée ; en cas d'échec, l'accueil n'affiche simplement pas ces rangées.
 */
object Trending {
    /** Films sortis récemment en VOD (souvent présents dans les flux IPTV), puis box-office France. */
    private const val VOD_URL = "https://www.allocine.fr/vod/"
    private const val MOVIES_URL = "https://www.allocine.fr/boxoffice/france/"
    private const val SERIES_URL = "https://www.allocine.fr/series/top/"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    class Titles(val movies: List<String>, val series: List<String>)

    private val TITLE = Regex("class=\"meta-title-link\"[^>]*>([^<]+)<")
    private val TAGS = Regex("<[^>]+>")
    private val YEAR = Regex("\\b(19|20)\\d{2}\\b")
    /** Sépare un titre classé de son année (caractère « séparateur d'unité », absent des titres). */
    const val SEP = '\u001F'

    /** Titres classés (dans l'ordre), depuis le cache du jour ou AlloCiné. Appel réseau : à faire hors du thread principal. */
    fun titles(ctx: Context): Titles? {
        val f = File(ctx.filesDir, "trending3.txt")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) parse(f.readText())?.let { return it }
        return try {
            // Sorties VOD récentes puis box-office : films récents, donc au plus tôt il y a deux ans
            // (écarte les anciennes versions homonymes) ; séries : année indiquée par AlloCiné.
            val minYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 2
            val movies = (runCatching { fetch(VOD_URL) }.getOrDefault(emptyList()) + fetch(MOVIES_URL))
                .map { it.substringBefore(SEP) }.distinct().map { it + SEP + "min:" + minYear }
            val t = Titles(movies, fetch(SERIES_URL))
            if (t.movies.isNotEmpty() || t.series.isNotEmpty()) {
                f.writeText("M\t" + t.movies.joinToString("\t") + "\nS\t" + t.series.joinToString("\t"))
            }
            t
        } catch (e: Exception) {
            if (f.exists()) parse(f.readText()) else null
        }
    }

    private fun parse(s: String): Titles? {
        val lines = s.lines()
        val m = lines.firstOrNull { it.startsWith("M\t") }?.split('\t')?.drop(1) ?: return null
        val se = lines.firstOrNull { it.startsWith("S\t") }?.split('\t')?.drop(1) ?: emptyList()
        return Titles(m.filter { it.isNotBlank() }, se.filter { it.isNotBlank() })
    }

    private fun fetch(url: String): List<String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9")
        try {
            if (c.responseCode !in 200..299) return emptyList()
            val html = c.inputStream.bufferedReader().use { it.readText() }
            // Titre + première année trouvée dans la fiche (ex. « Depuis 2021 »), séparés par SEP.
            return TITLE.findAll(html).map { m ->
                val title = decode(m.groupValues[1].trim())
                val after = html.substring(m.range.last + 1, minOf(html.length, m.range.last + 2500))
                    .replace(TAGS, " ")
                val year = YEAR.find(after)?.value
                if (year != null) title + SEP + year else title
            }.filter { it.isNotBlank() }.distinctBy { it.substringBefore(SEP) }.take(40).toList()
        } finally {
            c.disconnect()
        }
    }

    private fun decode(s: String) = s
        .replace("&#039;", "'").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&quot;", "\"").replace("&amp;", "&").replace("&nbsp;", " ")

    // --------------------------------------------------------- Correspondance avec le catalogue

    private val YEAR_SUFFIX = Regex("\\s*[-–(\\[]\\s*(19|20)\\d{2}\\s*[)\\]]?\\s*$")

    /** Clé de comparaison : sans année finale, sans accents ni ponctuation. */
    fun matchKey(title: String): String =
        ContentFilter.dedupKey(YEAR_SUFFIX.replace(ContentFilter.cleanName(title), ""))

    private val NAME_YEAR = Regex("(19|20)\\d{2}")

    /** Année figurant dans un nom du catalogue (« Dune - 2021 », « Dune (2021) »), sinon null. */
    private fun yearOf(name: String): Int? = NAME_YEAR.findAll(name).lastOrNull()?.value?.toIntOrNull()

    /**
     * Retrouve les titres classés dans le catalogue. Un titre classé peut porter une année
     * (« titre␟2021 ») ou une année minimale (« titre␟min:2025 ») : une version du catalogue
     * d'une autre époque (remake, homonyme) est alors écartée.
     */
    fun <T> match(ranked: List<String>, items: List<T>, name: (T) -> String, limit: Int = 10): List<T> {
        if (ranked.isEmpty() || items.isEmpty()) return emptyList()
        val byKey = HashMap<String, MutableList<T>>()
        for (it in items) byKey.getOrPut(matchKey(name(it))) { ArrayList() } += it
        val out = ArrayList<T>()
        for (entry in ranked) {
            val title = entry.substringBefore(SEP)
            val tag = entry.substringAfter(SEP, "")
            val k = matchKey(title)
            if (k.length < 2) continue
            val candidates = byKey[k] ?: continue
            val hit = candidates.firstOrNull { c ->
                val y = yearOf(name(c)) ?: return@firstOrNull true
                when {
                    tag.startsWith("min:") -> y >= (tag.removePrefix("min:").toIntOrNull() ?: 0)
                    tag.toIntOrNull() != null -> kotlin.math.abs(y - tag.toInt()) <= 1
                    else -> true
                }
            } ?: continue
            if (hit !in out) out += hit
            if (out.size >= limit) break
        }
        return out
    }
}
