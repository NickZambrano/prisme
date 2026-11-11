package com.fluxfr.tv.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.BufferedInputStream
import java.io.InputStreamReader
import java.net.URLEncoder
import javax.net.ssl.SSLException

class SeriesInfo(val plot: String?, val cover: String?, val episodes: List<Episode>)

/** Fiche détaillée d'un film (get_vod_info). Champs nuls/vides si le fournisseur ne les donne pas. */
class MovieInfo(
    val plot: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val releaseDate: String?,
    val year: String?,
    val durationSecs: Long,
    val rating: String?,
    val backdrop: String?,
    val cover: String?,
    /** Identifiant YouTube (11 caractères) de la bande-annonce. */
    val trailerYoutubeId: String?,
    /** Format vidéo / audio (analyse ffprobe du panel : info.video / info.audio). */
    val av: AvFormat = AvFormat.NONE,
)

/** Résultat brut d'un get_vod_info pour l'enrichissement (audio + genre + identifiants). */
class VodDetails(
    val audioLanguages: List<String>,
    val genre: String?,
    val tmdbId: String?,
    val imdbId: String?,
    val year: Int?,
)

/** Client de l'API "Xtream Codes" (player_api.php), utilisée par la plupart des fournisseurs IPTV. */
class XtreamClient(server: String, private val user: String, private val pass: String) {
    /** Peut passer de https à http si le certificat du serveur est invalide (fréquent en IPTV). */
    var base: String = normalize(server)
        private set

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun <T> read(url: String, block: (JsonReader) -> T): T = try {
        readOnce(url, block)
    } catch (e: SSLException) {
        if (!base.startsWith("https://", ignoreCase = true)) throw e
        val httpBase = "http://" + base.substringAfter("://")
        val retryUrl = httpBase + url.removePrefix(base)
        base = httpBase
        readOnce(retryUrl, block)
    }

    private fun api(action: String? = null, extra: String = "") = buildString {
        append(base).append("/player_api.php?username=").append(enc(user))
        append("&password=").append(enc(pass))
        if (action != null) append("&action=").append(action)
        append(extra)
    }

    private fun <T> readOnce(url: String, block: (JsonReader) -> T): T {
        val c = Http.open(url)
        try {
            val reader = JsonReader(InputStreamReader(BufferedInputStream(c.inputStream, 64 * 1024), Charsets.UTF_8))
            reader.isLenient = true
            return reader.use(block)
        } finally {
            c.disconnect()
        }
    }

    /** null si les identifiants sont bons, sinon un message d'erreur. */
    fun authenticate(): String? = read(api()) { r ->
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            return@read "Réponse inattendue : est-ce bien un serveur Xtream ?"
        }
        var auth = false
        var status: String? = null
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "user_info" && r.peek() == JsonToken.BEGIN_OBJECT) {
                val m = Json.readFlat(r)
                auth = m["auth"] == "1" || m["auth"] == "true"
                status = m["status"]
            } else r.skipValue()
        }
        r.endObject()
        when {
            !auth -> "Identifiants refusés par le serveur"
            status != null && !status.equals("Active", ignoreCase = true) -> "Compte : $status"
            else -> null
        }
    }

    /** Liste ordonnée (id, nom) des catégories. */
    fun categories(action: String): List<Pair<String, String>> = read(api(action)) { r ->
        val out = ArrayList<Pair<String, String>>()
        Json.forEachObject(r) { m ->
            val id = m["category_id"] ?: return@forEachObject
            out += id to (m["category_name"] ?: "")
        }
        out
    }

    fun forEachItem(action: String, extra: String = "", fn: (Map<String, String?>) -> Unit) = read(api(action, extra)) { r ->
        Json.forEachObject(r, fn)
    }

    /**
     * Langues des pistes audio d'un film, d'après get_vod_info (analyse ffprobe faite par le panel).
     * Cherche toute valeur "language" (ou "lang") située sous une clé "audio". Liste vide si le
     * fournisseur ne donne pas l'information.
     */
    fun vodAudioLanguages(vodId: String): List<String> = read(api("get_vod_info", "&vod_id=${enc(vodId)}")) { r ->
        val out = ArrayList<String>()
        collectAudioLanguages(r, insideAudio = false, out = out)
        out
    }

    /**
     * Un seul appel get_vod_info pour l'enrichissement en arrière-plan : langues audio, genre brut,
     * identifiants TMDB / IMDB et année (champs de "info", à défaut de "movie_data").
     */
    fun vodDetails(vodId: String): VodDetails = read(api("get_vod_info", "&vod_id=${enc(vodId)}")) { r ->
        val langs = ArrayList<String>()
        val fields = HashMap<String, String>()
        if (r.peek() == JsonToken.BEGIN_OBJECT) {
            r.beginObject()
            while (r.hasNext()) {
                val k = r.nextName().lowercase()
                if ((k == "info" || k == "movie_data") && r.peek() == JsonToken.BEGIN_OBJECT) {
                    r.beginObject()
                    while (r.hasNext()) {
                        val f = r.nextName().lowercase()
                        when {
                            r.peek() == JsonToken.STRING || r.peek() == JsonToken.NUMBER -> {
                                val v = r.nextString().trim()
                                if (v.isNotEmpty() && v != "0" && !v.equals("null", true)) fields.putIfAbsent(f, v)
                            }
                            (f == "genre" || f == "genres") && r.peek() == JsonToken.BEGIN_ARRAY -> {
                                val values = ArrayList<String>()
                                r.beginArray()
                                while (r.hasNext()) {
                                    if (r.peek() == JsonToken.BEGIN_OBJECT) Json.readFlat(r)["name"]?.let(values::add)
                                    else Json.primitive(r)?.let(values::add)
                                }
                                r.endArray()
                                if (values.isNotEmpty()) fields.putIfAbsent("genre", values.joinToString(", "))
                            }
                            else -> collectAudioLanguages(r, f.startsWith("audio"), langs)
                        }
                    }
                    r.endObject()
                } else collectAudioLanguages(r, k.startsWith("audio"), langs)
            }
            r.endObject()
        } else r.skipValue()
        VodDetails(
            audioLanguages = langs,
            genre = fields["genre"] ?: fields["genres"],
            tmdbId = (fields["tmdb_id"] ?: fields["tmdb"])?.takeIf { TMDB.matches(it) },
            imdbId = (fields["imdb_id"] ?: fields["imdb"])?.let { IMDB.find(it)?.value },
            year = (fields["year"] ?: fields["releasedate"] ?: fields["release_date"])
                ?.let { YEAR.find(it)?.value?.toIntOrNull() },
        )
    }

    private fun collectAudioLanguages(r: JsonReader, insideAudio: Boolean, out: MutableList<String>) {
        when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName()
                    val lower = k.lowercase()
                    if (insideAudio && (lower == "language" || lower == "lang") &&
                        (r.peek() == JsonToken.STRING)
                    ) {
                        out += r.nextString()
                    } else {
                        collectAudioLanguages(r, insideAudio || lower.startsWith("audio"), out)
                    }
                }
                r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) collectAudioLanguages(r, insideAudio, out)
                r.endArray()
            }
            else -> r.skipValue()
        }
    }

    /** Fiche d'un film (résumé, casting, bande-annonce…), gardée en mémoire (100 dernières). */
    fun vodInfo(vodId: String): MovieInfo {
        val cacheKey = "$base|$user|$vodId"
        synchronized(vodCache) { vodCache[cacheKey] }?.let { return it }
        val info = read(api("get_vod_info", "&vod_id=${enc(vodId)}")) { r -> parseVodInfo(r) }
        synchronized(vodCache) { vodCache[cacheKey] = info }
        return info
    }

    private fun parseVodInfo(r: JsonReader): MovieInfo {
        val m = HashMap<String, List<String>>()
        val video = ArrayList<Pair<String, String>>()
        val audio = ArrayList<Pair<String, String>>()
        if (r.peek() == JsonToken.BEGIN_OBJECT) {
            r.beginObject()
            while (r.hasNext()) {
                val top = r.nextName()
                if (top == "info" && r.peek() == JsonToken.BEGIN_OBJECT) {
                    r.beginObject()
                    while (r.hasNext()) {
                        val k = r.nextName().lowercase()
                        when {
                            k == "video" && r.peek() == JsonToken.BEGIN_OBJECT -> flatten(r, "", video, 0)
                            k == "audio" && r.peek() == JsonToken.BEGIN_OBJECT -> flatten(r, "", audio, 0)
                            else -> {
                                val values = ArrayList<String>()
                                readValues(r, values, depth = 0)
                                m[k] = values
                            }
                        }
                    }
                    r.endObject()
                } else if ((top == "video" || top == "audio") && r.peek() == JsonToken.BEGIN_OBJECT) {
                    flatten(r, "", if (top == "video") video else audio, 0)
                } else r.skipValue()
            }
            r.endObject()
        } else r.skipValue()

        fun first(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            m[k]?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && it != "0" && !it.equals("null", true) }
        }
        fun joined(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            m[k]?.map { it.trim() }?.filter { it.isNotEmpty() && !it.equals("null", true) }
                ?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }

        val release = first("releasedate", "release_date", "air_date")
        val year = first("year")?.let { YEAR.find(it)?.value } ?: release?.let { YEAR.find(it)?.value }
        val duration = first("duration_secs")?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
            ?: first("duration")?.let(::parseDuration)
            ?: first("episode_run_time", "runtime")?.toDoubleOrNull()?.let { (it * 60).toLong() }
            ?: 0L
        val rating = first("rating", "rating_10based")?.let { r ->
            val d = r.replace(',', '.').toDoubleOrNull()
            when {
                d == null -> r
                d <= 0.0 -> null
                else -> "%.1f".format(java.util.Locale.FRANCE, d).removeSuffix(",0")
            }
        }
        return MovieInfo(
            plot = first("plot", "description", "overview"),
            genre = joined("genre", "genres"),
            director = joined("director", "directors"),
            cast = joined("cast", "actors"),
            releaseDate = release,
            year = year,
            durationSecs = duration,
            rating = rating,
            backdrop = m["backdrop_path"]?.firstOrNull { it.startsWith("http", true) }
                ?: first("backdrop"),
            cover = first("cover_big", "movie_image", "cover"),
            trailerYoutubeId = first("youtube_trailer", "trailer")?.let(::youtubeId),
            av = AvFormat.fromStreams(video, audio),
        )
    }

    /** Toutes les valeurs simples d'un objet (récursif), avec leur clé (en minuscules). */
    private fun flatten(r: JsonReader, key: String, out: MutableList<Pair<String, String>>, depth: Int) {
        when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName().lowercase()
                    if (depth < 4) flatten(r, k, out, depth + 1) else r.skipValue()
                }
                r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) if (depth < 4) flatten(r, key, out, depth + 1) else r.skipValue()
                r.endArray()
            }
            else -> Json.primitive(r)?.let { out += key to it }
        }
    }

    /** Valeurs simples d'un champ (une valeur, ou plusieurs si c'est un tableau). */
    private fun readValues(r: JsonReader, out: MutableList<String>, depth: Int) {
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) if (depth < 2) readValues(r, out, depth + 1) else r.skipValue()
                r.endArray()
            }
            JsonToken.BEGIN_OBJECT -> r.skipValue()
            else -> Json.primitive(r)?.let(out::add)
        }
    }

    fun liveUrl(id: String, ext: String) = "$base/live/${enc(user)}/${enc(pass)}/$id.$ext"
    fun movieUrl(id: String, ext: String) = "$base/movie/${enc(user)}/${enc(pass)}/$id.$ext"
    fun episodeUrl(id: String, ext: String) = "$base/series/${enc(user)}/${enc(pass)}/$id.$ext"

    fun seriesInfo(seriesId: String): SeriesInfo = read(api("get_series_info", "&series_id=${enc(seriesId)}")) { r ->
        var plot: String? = null
        var cover: String? = null
        val eps = ArrayList<Episode>()
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            return@read SeriesInfo(null, null, eps)
        }
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "info" -> if (r.peek() == JsonToken.BEGIN_OBJECT) {
                    val m = Json.readFlat(r)
                    plot = m["plot"]
                    cover = m["cover"]
                } else r.skipValue()
                "episodes" -> when (r.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            val season = r.nextName().toIntOrNull() ?: 0
                            readEpisodeArray(r, season, eps)
                        }
                        r.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        r.beginArray()
                        while (r.hasNext()) readEpisodeArray(r, 0, eps)
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        SeriesInfo(plot, cover, eps.sortedWith(compareBy({ it.season }, { it.number })))
    }

    private fun readEpisodeArray(r: JsonReader, seasonHint: Int, out: MutableList<Episode>) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return }
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() == JsonToken.BEGIN_OBJECT) readEpisode(r, seasonHint)?.let(out::add) else r.skipValue()
        }
        r.endArray()
    }

    private fun readEpisode(r: JsonReader, seasonHint: Int): Episode? {
        var id: String? = null
        var num = 0
        var title = ""
        var ext = "mp4"
        var season = seasonHint
        var image: String? = null
        var plot: String? = null
        var duration = 0L
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            if (k == "info") {
                if (r.peek() == JsonToken.BEGIN_OBJECT) {
                    val m = Json.readFlat(r)
                    image = m["movie_image"]
                    plot = m["plot"]
                    duration = m["duration_secs"]?.toLongOrNull() ?: 0L
                } else r.skipValue()
                continue
            }
            val v = Json.primitive(r)
            when (k) {
                "id" -> id = v
                "episode_num" -> num = v?.toIntOrNull() ?: 0
                "title" -> title = v ?: ""
                "container_extension" -> ext = v?.takeIf { it.isNotBlank() } ?: "mp4"
                "season" -> season = v?.toIntOrNull() ?: season
            }
        }
        r.endObject()
        val epId = id ?: return null
        return Episode(epId, season, num, title, episodeUrl(epId, ext), image, plot, duration)
    }

    companion object {
        private val YEAR = Regex("(19|20)\\d{2}")
        private val TMDB = Regex("\\d{1,9}")
        private val IMDB = Regex("tt\\d{5,10}")
        private val YT_ID = Regex("^[A-Za-z0-9_-]{11}$")
        private val YT_URL = Regex("(?:v=|vi=|youtu\\.be/|/embed/|/shorts/|/v/|/live/)([A-Za-z0-9_-]{11})")

        /** Cache LRU des fiches films. */
        private val vodCache = object : LinkedHashMap<String, MovieInfo>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MovieInfo>?) = size > 100
        }

        /** « dQw4w9WgXcQ », « https://www.youtube.com/watch?v=… », « youtu.be/… » → l'identifiant à 11 caractères. */
        fun youtubeId(raw: String): String? {
            val s = raw.trim()
            if (YT_ID.matches(s)) return s
            return YT_URL.find(s)?.groupValues?.get(1)
        }

        /** « 01:52:30 », « 112 min », « 6750 » (secondes) → secondes. */
        private fun parseDuration(s: String): Long? {
            val parts = s.trim().split(':')
            if (parts.size in 2..3 && parts.all { it.trim().toIntOrNull() != null }) {
                return parts.fold(0L) { acc, p -> acc * 60 + p.trim().toLong() }.let {
                    // « 1:52 » = heures:minutes ; « 52:30 » = minutes:secondes
                    if (parts.size == 2 && parts[0].trim().toInt() <= 5) it * 60 else it
                }.takeIf { it > 0 }
            }
            val n = Regex("\\d+").find(s)?.value?.toLongOrNull() ?: return null
            return when {
                "min" in s.lowercase() -> n * 60
                n in 1..400 -> n * 60
                else -> n
            }.takeIf { it > 0 }
        }

        /**
         * Message affiché quand la vérification du compte échoue. Beaucoup de panels répondent 401 / 403
         * (au lieu de auth = 0) à de mauvais identifiants : on le dit clairement plutôt que « HTTP 401 ».
         */
        fun loginError(e: Exception): String {
            val m = e.message.orEmpty()
            return when {
                Regex("""HTTP 40[13]\b""").containsMatchIn(m) ->
                    "Identifiants refusés par le fournisseur : vérifie le nom d'utilisateur et le mot de passe " +
                        "(majuscules, espace en trop), et que l'adresse est bien celle du serveur Xtream."
                Regex("""HTTP 404\b""").containsMatchIn(m) ->
                    "Adresse du serveur incorrecte : ce n'est pas un serveur Xtream (erreur 404)."
                e is java.net.UnknownHostException -> "Serveur introuvable : vérifie l'adresse."
                else -> "Connexion impossible : ${m.ifBlank { e.javaClass.simpleName }}"
            }
        }

        /**
         * Mot de passe tel qu'on l'envoie : sans les espaces / retours à la ligne ajoutés autour par le
         * clavier d'un téléphone ou un copier-coller (un mot de passe Xtream n'en contient pas aux bords).
         */
        fun cleanPass(s: String): String = s.trim()

        fun normalize(s: String): String {
            var b = s.trim().trimEnd('/')
            if (!b.startsWith("http://", true) && !b.startsWith("https://", true)) b = "http://$b"
            b = b.replace(Regex("/(player_api|get)\\.php.*$", RegexOption.IGNORE_CASE), "")
            return b.trimEnd('/')
        }
    }
}
