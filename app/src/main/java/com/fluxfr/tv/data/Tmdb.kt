package com.fluxfr.tv.data

import android.content.Context
import com.fluxfr.tv.BuildConfig
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/** Une rangée prête à afficher (titre + éléments du catalogue, dans l'ordre). */
data class Shelf<T>(val title: String, val items: List<T>)

// =============================================================================== TMDB (réseau + cache disque)

internal object Tmdb {
    const val MOVIE = "movie"
    const val TV = "tv"
    private const val BASE = "https://api.themoviedb.org/3"
    /** Fiches, recommandations, recherches : 7 jours. */
    const val TTL_MS = 7L * 24 * 3600 * 1000
    /** Listes (tendances, populaires…) : 6 h. */
    const val LIST_TTL_MS = 6L * 3600 * 1000
    /** Requêtes TMDB simultanées au plus. */
    const val CONCURRENCY = 4
    /** Limite partagée par tous les appels TMDB de l'appli (recommandations, listes). */
    val gate = Semaphore(CONCURRENCY)

    /** Une passe de rafraîchissement : budget de requêtes, coupure à la première erreur réseau. */
    class Session(val ctx: Context, val refreshStale: Boolean, maxRequests: Int) {
        val budget = AtomicInteger(maxRequests)
        @Volatile var offline = false
        val remaining get() = max(0, budget.get())
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun dir(ctx: Context) = File(ctx.filesDir, "tmdb").also { it.mkdirs() }

    private fun cacheFile(ctx: Context, id: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(ctx), h.take(24) + ".json")
    }

    private fun write(f: File, text: String) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private fun read(f: File): String? = runCatching { f.readText() }.getOrNull()

    /**
     * GET /3[path] en français, mis en cache [ttlMs] (7 jours par défaut). Cache périmé : réutilisé hors ligne,
     * en cas d'échec, budget épuisé, ou si le rafraîchissement n'est pas encore dû ([Session.refreshStale]).
     * La clé d'API n'apparaît jamais dans le nom du fichier de cache ni dans un journal.
     */
    fun get(
        s: Session,
        path: String,
        vararg params: Pair<String, String>,
        cacheOnly: Boolean = false,
        ttlMs: Long = TTL_MS,
    ): String? {
        // Français par défaut ; un paramètre « language » explicite (ex. en-US) l'emporte.
        val query = params.toList().let { p -> if (p.any { it.first == "language" }) p else p + ("language" to "fr-FR") }
            .sortedBy { it.first }
            .joinToString("&") { enc(it.first) + "=" + enc(it.second) }
        val f = cacheFile(s.ctx, "$path?$query")
        val exists = f.exists()
        if (exists && System.currentTimeMillis() - f.lastModified() < ttlMs) return read(f)
        val key = BuildConfig.TMDB_API_KEY
        if (cacheOnly || key.isBlank() || s.offline || (exists && !s.refreshStale) || s.budget.getAndDecrement() <= 0) {
            return if (exists) read(f) else null
        }
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$BASE$path?api_key=${enc(key)}&$query").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
            }
            when (val code = c.responseCode) {
                in 200..299 -> c.inputStream.bufferedReader().use { it.readText() }.also { write(f, it) }
                404 -> "{}".also { write(f, it) }
                else -> {
                    if (code == 401 || code == 429) s.offline = true
                    if (exists) read(f) else null
                }
            }
        } catch (e: Exception) {
            s.offline = true
            if (exists) read(f) else null
        } finally {
            c?.disconnect()
        }
    }

    fun info(s: Session, type: String, id: String, cacheOnly: Boolean): TmdbInfo? =
        TmdbParse.info(get(s, "/$type/$id", "append_to_response" to "credits,keywords", cacheOnly = cacheOnly), type)

    // ---- Identifiants TMDB trouvés par recherche (titres sans tmdb_id chez le fournisseur), gardés pour toujours.
    private val resolved = ConcurrentHashMap<String, String>()
    @Volatile private var resolvedLoaded = false

    private fun idsFile(ctx: Context) = File(dir(ctx), "ids.tsv")

    fun resolvedId(ctx: Context, key: String): String? {
        if (!resolvedLoaded) synchronized(this) {
            if (!resolvedLoaded) {
                runCatching {
                    idsFile(ctx).takeIf { it.exists() }?.forEachLine { l ->
                        val p = l.split('\t')
                        if (p.size >= 2 && p[1].isNotBlank()) resolved[p[0]] = p[1]
                    }
                }
                resolvedLoaded = true
            }
        }
        return resolved[key]
    }

    @Synchronized
    fun saveResolved(ctx: Context, key: String, id: String) {
        if (resolved.put(key, id) == id) return
        runCatching { idsFile(ctx).appendText("$key\t$id\n") }
    }

    /**
     * Identifiant TMDB déjà connu d'un titre du catalogue ([key] = "movie:<id>" / "series:<id>") : celui du
     * fournisseur (VodMeta), sinon celui trouvé par une recherche précédente. Jamais de réseau. Hors du thread principal.
     */
    fun knownId(ctx: Context, key: String): String? {
        VodMeta.load(ctx.applicationContext)
        return VodMeta.get(key)?.tmdbId?.trim()?.takeIf { (it.toLongOrNull() ?: 0) > 0 }
            ?: resolvedId(ctx.applicationContext, key)
    }

    // ---- Visuels et infos d'affichage (interface tactile) : même requête que [info] (/type/id + credits,keywords),
    // donc même fichier de cache ; au plus UNE requête par titre, aucune quand l'identifiant TMDB est inconnu.

    private val arts = ConcurrentHashMap<String, TmdbArt>()

    /** Déjà en mémoire (aucune lecture disque) : affichage immédiat au retour sur un écran. */
    fun cachedArt(type: String, id: String): TmdbArt? = arts["$type:$id"]

    /**
     * Fiche d'affichage de [id] ([type] = [MOVIE] / [TV]) : visuel paysage (backdrop), année, genres en français,
     * note. Cache mémoire, puis disque (7 jours, réutilisé périmé), puis 1 requête au plus. Sur IO, limité par [gate].
     */
    suspend fun art(ctx: Context, type: String, id: String): TmdbArt? {
        val k = "$type:$id"
        arts[k]?.let { return it }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            gate.acquire()
            try {
                arts[k] ?: run {
                    val s = Session(ctx.applicationContext, refreshStale = false, maxRequests = 1)
                    val json = runCatching { get(s, "/$type/$id", "append_to_response" to "credits,keywords") }.getOrNull()
                    parseArt(json, type)?.also { arts[k] = it }
                }
            } finally {
                gate.release()
            }
        }
    }

    /** Saga TMDB (belongs_to_collection) d'un film, lue SEULEMENT dans le cache disque : "" = aucune, null = inconnu. */
    fun cachedCollection(ctx: Context, movieKey: String): String? {
        val id = knownId(ctx, movieKey) ?: return null
        val info = runCatching {
            info(Session(ctx.applicationContext, refreshStale = false, maxRequests = 0), MOVIE, id, cacheOnly = true)
        }.getOrNull() ?: return null
        return info.collection.orEmpty()
    }

    const val IMAGE_BASE = "https://image.tmdb.org/t/p/"

    /** Lecture de /movie/{id} ou /tv/{id} (pure, testée). null si la réponse est vide ou illisible. */
    fun parseArt(json: String?, type: String): TmdbArt? {
        if (json.isNullOrBlank()) return null
        val o = runCatching { MiniJson.parse(json) as? Map<*, *> }.getOrNull() ?: return null
        if (o["id"] == null) return null
        val backdrop = (o["backdrop_path"] as? String)?.takeIf { it.startsWith("/") }?.let { IMAGE_BASE + "w1280" + it }
        val poster = (o["poster_path"] as? String)?.takeIf { it.startsWith("/") }?.let { IMAGE_BASE + "w500" + it }
        val date = o[if (type == MOVIE) "release_date" else "first_air_date"] as? String
        val genres = (o["genres"] as? List<*>).orEmpty()
            .mapNotNull { ((it as? Map<*, *>)?.get("name") as? String)?.trim()?.takeIf { n -> n.isNotEmpty() } }
        val vote = (o["vote_average"] as? Number)?.toDouble()
        val votes = (o["vote_count"] as? Number)?.toInt() ?: 0
        return TmdbArt(
            backdrop = backdrop,
            poster = poster,
            year = date?.take(4)?.toIntOrNull(),
            genres = genres,
            rating = vote?.takeIf { it > 0 && votes >= 5 }?.let { String.format(java.util.Locale.ROOT, "%.1f", it) },
        )
    }
}

/** Ce que TMDB apporte à l'affichage d'un titre : visuel paysage (null si aucun), affiche, année, genres (fr), note « 7.6 ». */
data class TmdbArt(
    val backdrop: String?,
    val poster: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: String? = null,
)
