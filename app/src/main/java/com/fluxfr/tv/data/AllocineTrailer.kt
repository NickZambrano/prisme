package com.fluxfr.tv.data

import android.content.Context
import android.util.Log
import com.fluxfr.tv.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Bandes-annonces françaises d'AlloCiné (fichiers mp4 directs), trouvées par le titre du film :
 * recherche → fiche du film → page du lecteur. Toutes les erreurs donnent null.
 * Cache mémoire (LRU) + petit cache disque (7 jours), y compris pour « introuvable ».
 */
object AllocineTrailer {
    private const val TAG = "FluxTrailer"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private const val TIMEOUT_MS = 8_000
    private const val CACHE_FILE = "allocine_trailers4.tsv"
    private const val CACHE_TTL_MS = 7L * 24 * 3600 * 1000
    private const val MAX_DISK_ENTRIES = 500
    private const val NOT_FOUND = "-"

    private class Entry(val url: String, val at: Long)

    private val memory = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 200
    }
    private var diskLoaded = false
    private val lock = Any()

    private val YEAR_SUFFIX = Regex("""[\s\-–:|]*[(\[]?\s*((?:19|20)\d{2})\s*[)\]]?\s*$""")
    private val TAGS = Regex(
        """(?i)\b(4k|uhd|fhd|hd|sd|hdr|hdr10|dv|1080p|720p|2160p|480p|multi|vostfr|vost|vf|vff|vfq|vfi|truefrench|french|fr|x264|x265|hevc|web(?:-?dl|rip)?|bluray|imax|3d)\b"""
    )
    private val PREFIX = Regex(
        """^\s*(?:[\[|(][^\]|)]{1,12}[\]|)]\s*|(?:FR|VF|VFF|VFQ|VOSTFR|MULTI|EN|4K|UHD|FHD|HD|SD|NF|AMZN)\s*[-:|]\s*)"""
    )

    /** Titre « propre » pour la recherche, et l'année trouvée dans le titre s'il y en a une. */
    fun cleanTitle(raw: String): Pair<String, Int?> {
        var t = raw.trim()
        var year: Int? = null
        repeat(2) { t = PREFIX.replace(t, "") }
        // Balises entre crochets / parenthèses (hors année) : [MULTI], (4K)…
        t = Regex("""[\[(]\s*([^\])]*)\s*[\])]""").replace(t) { m ->
            val inner = m.groupValues[1]
            if (inner.matches(Regex("""(19|20)\d{2}"""))) m.value else " "
        }
        YEAR_SUFFIX.find(t)?.let { m ->
            year = m.groupValues[1].toIntOrNull()
            t = t.substring(0, m.range.first)
        }
        t = TAGS.replace(t, " ")
        // Une année restée en fin après retrait des balises.
        YEAR_SUFFIX.find(t)?.let { m ->
            if (year == null) year = m.groupValues[1].toIntOrNull()
            if (m.range.first > 0) t = t.substring(0, m.range.first)
        }
        t = t.replace(Regex("""\s+"""), " ").trim().trim('-', ':', '|', '.', ' ')
        return (t.ifBlank { raw.trim() }) to year
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ").trim()

    /**
     * URL mp4 de la bande-annonce, ou null. Bloquant (réseau) : appeler sur Dispatchers.IO.
     * [year] : année connue (prioritaire sur celle déduite du titre).
     */
    fun find(context: Context, title: String, year: Int?): String? {
        init(context)
        return find(title, year)
    }

    /** Active le cache disque (filesDir). Sans appel, seul le cache mémoire sert. */
    fun init(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
    }

    @Volatile private var appCtx: Context? = null

    fun find(title: String, year: Int?): String? = try {
        val (clean, titleYear) = cleanTitle(title)
        val y = year ?: titleYear
        val key = "${normalize(clean)}|${y ?: ""}"
        val cached = cacheGet(key)
        val ref = if (cached != null) {
            log("cache « $clean » ($y) → ${if (cached == NOT_FOUND) "aucune" else cached}")
            cached.takeIf { it != NOT_FOUND }
        } else {
            val url = lookup(clean, y)
            cachePut(key, url ?: NOT_FOUND)
            url
        }
        // « dm:<id> » : vidéo hébergée chez Dailymotion, dont l'URL HLS signée expire : résolue à chaque fois.
        if (ref != null && ref.startsWith(DM)) dailymotionHls(ref.removePrefix(DM)) else ref
    } catch (e: Throwable) {
        log("échec « $title » : $e")
        null
    }

    private val TAGS_RE = Regex("<[^>]+>")
    private val SPACES_RE = Regex("\\s+")
    private val CMEDIA_RE = Regex("""cmedia=(\d+)""")

    /**
     * Meilleure vidéo d'une fiche AlloCiné : chaque lien « cmedia=… » est suivi de son titre
     * (« Bande-annonce VF », « Teaser VO STFR », « Extrait vidéo VO »…). Priorité à la VF, puis à la
     * VOSTFR ; bande-annonce > teaser ; extraits et interviews écartés. [default] : vidéo principale.
     */
    internal fun bestVideo(html: String, default: String?): String? {
        // Vidéo principale de langue inconnue : bande-annonce probable (score 33). Une VF ou une VOSTFR
        // listée sur la fiche passe devant ; une VO seule ne la remplace pas.
        var best: String? = default
        var bestScore = if (default != null) 33 else Int.MIN_VALUE
        val seen = HashSet<String>()
        val links = CMEDIA_RE.findAll(html).toList()
        for ((i, m) in links.withIndex()) {
            val id = m.groupValues[1]
            // Titre de CETTE vidéo : texte jusqu'au lien suivant (sans déborder sur la vignette d'après).
            val end = minOf(html.length, m.range.last + 700, links.getOrNull(i + 1)?.range?.first ?: html.length)
            val text = SPACES_RE.replace(TAGS_RE.replace(html.substring(m.range.last + 1, maxOf(m.range.last + 1, end)), " "), " ")
                .take(220).uppercase()
            if (!seen.add(id + "|" + text.take(40))) continue
            val kind = when {
                "BANDE-ANNONCE" in text || "BANDE ANNONCE" in text -> 10
                "TEASER" in text -> 6
                else -> continue // extraits, interviews, making-of, page sans titre
            }
            val lang = when {
                Regex("""\bVF\b|\bVFF\b|\bVERSION FRAN""").containsMatchIn(text) -> 100
                "VOSTFR" in text || "VO STFR" in text || "VOST" in text -> 50
                Regex("""\bVO\b""").containsMatchIn(text) -> 0
                else -> 20
            }
            val score = lang + kind + (if (id == default) 3 else 0)
            if (score > bestScore) { bestScore = score; best = id }
        }
        return best
    }

    private fun lookup(title: String, year: Int?): String? {
        // Autocomplétion AlloCiné : vrais résultats (la page de recherche HTML ne les contient pas).
        val json = get("https://www.allocine.fr/_/autocomplete/" + URLEncoder.encode(title, "UTF-8").replace("+", "%20"))
            ?: return null.also { log("recherche « $title » : échec réseau") }
        val (filmId, video) = pickFilm(json, title, year)
            ?: return null.also { log("recherche « $title » ($year) : aucun film correspondant") }
        // Fiche du film : liste de ses vidéos avec leur langue → bande-annonce VF de préférence, sinon VOSTFR.
        val page = get("https://www.allocine.fr/film/fichefilm_gen_cfilm=$filmId.html")
        val mediaId = page?.let { bestVideo(it, video) } ?: video
            ?: return null.also { log("fiche $filmId (« $title ») : pas de vidéo") }
        val player = get("https://www.allocine.fr/video/player_gen_cmedia=$mediaId&cfilm=$filmId.html")
            ?: return null.also { log("lecteur $mediaId : échec réseau") }
        val mp4s = Regex("""https?:(?:\\?/){2}[^"'\s<>]+?\.mp4""").findAll(player)
            .map { it.value.replace("\\/", "/") }
            .distinct().toList()
        val best = mp4s.firstOrNull { "_hd_" in it } ?: mp4s.firstOrNull()
            // Vidéos récentes : hébergées chez Dailymotion (id dans le data-model du lecteur).
            ?: Regex("""idDailymotion(?:&quot;|")\s*:\s*(?:&quot;|")([a-zA-Z0-9]+)""").find(player)
                ?.groupValues?.get(1)?.let { DM + it }
        log("« $title » ($year) → film $filmId, média $mediaId → ${best ?: "aucune vidéo"}")
        return best
    }

    private const val DM = "dm:"

    /** URL HLS (m3u8) d'une vidéo Dailymotion, via l'API publique de métadonnées du lecteur. */
    private fun dailymotionHls(id: String): String? {
        val json = get("https://www.dailymotion.com/player/metadata/video/$id") ?: return null
        val url = runCatching {
            org.json.JSONObject(json).getJSONObject("qualities").getJSONArray("auto")
                .getJSONObject(0).getString("url")
        }.getOrNull()
        log("Dailymotion $id → ${url?.substringBefore('?') ?: "aucun flux"}")
        return url
    }

    /**
     * Film dont le titre (français ou original) correspond exactement au titre cherché ;
     * entre homonymes (remake, version animée / live), l'année la plus proche l'emporte.
     * Renvoie (id du film, id de la vidéo par défaut ou null).
     */
    private fun pickFilm(json: String, title: String, year: Int?): Pair<String, String?>? {
        val results = runCatching { org.json.JSONObject(json).optJSONArray("results") }.getOrNull() ?: return null
        val want = normalize(title)
        var best: Pair<String, String?>? = null
        var bestScore = Int.MAX_VALUE
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            if (r.optString("entity_type") != "movie") continue
            val labels = listOf(r.optString("label"), r.optString("original_label")).map(::normalize)
            if (want !in labels) continue
            val data = r.optJSONObject("data")
            val y = data?.optString("year")?.toIntOrNull()
            val score = if (year == null || y == null) 50 else kotlin.math.abs(y - year)
            if (year != null && y != null && score > 1) continue
            if (score < bestScore) {
                bestScore = score
                val video = data?.optLong("default_video", 0L)?.takeIf { it > 0 }?.toString()
                best = r.optString("entity_id") to video
            }
        }
        return best
    }

    private fun get(url: String): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            }
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    // ------------------------------------------------------------------ Cache

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, CACHE_FILE)

    private fun cacheGet(key: String): String? = synchronized(lock) {
        appCtx?.let(::loadDisk)
        val e = memory[key] ?: return null
        if (System.currentTimeMillis() - e.at > CACHE_TTL_MS) {
            memory.remove(key)
            return null
        }
        e.url
    }

    private fun cachePut(key: String, url: String) = synchronized(lock) {
        val ctx = appCtx
        ctx?.let(::loadDisk)
        memory[key] = Entry(url, System.currentTimeMillis())
        if (ctx != null) runCatching {
            val now = System.currentTimeMillis()
            val lines = memory.entries
                .filter { now - it.value.at <= CACHE_TTL_MS }
                .takeLast(MAX_DISK_ENTRIES)
                .joinToString("\n") { "${it.key}\t${it.value.url}\t${it.value.at}" }
            val f = cacheFile(ctx)
            val tmp = File(f.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(lines)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private fun loadDisk(ctx: Context) {
        if (diskLoaded) return
        diskLoaded = true
        runCatching {
            val f = cacheFile(ctx)
            if (!f.exists()) return
            val now = System.currentTimeMillis()
            f.readLines().forEach { line ->
                val p = line.split('\t')
                if (p.size < 3) return@forEach
                val at = p[2].toLongOrNull() ?: return@forEach
                if (now - at <= CACHE_TTL_MS && p[0] !in memory) memory[p[0]] = Entry(p[1], at)
            }
        }
    }

    private fun log(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "AlloCiné : $msg")
    }
}
