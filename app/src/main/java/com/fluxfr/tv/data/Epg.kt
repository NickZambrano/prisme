package com.fluxfr.tv.data

import android.content.Context
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Un programme du guide TV. */
data class EpgProgram(val title: String, val description: String?, val startMs: Long, val endMs: Long) {
    fun progress(now: Long = System.currentTimeMillis()): Float =
        if (endMs > startMs) ((now - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f) else 0f

    fun isNow(now: Long = System.currentTimeMillis()) = now in startMs until endMs
}

/** Règles pures (testées) du cache du guide : un échec réseau n'est jamais mémorisé comme un guide vide. */
internal object EpgCacheRules {
    /** Après un échec, nouvel essai au bout de ce délai (au lieu de la durée de vie normale du cache). */
    const val FAILURE_RETRY_MS = 30_000L

    /**
     * Date à noter dans le cache pour qu'une entrée expire après [ttl] (succès) ou [FAILURE_RETRY_MS] (échec) ;
     * la lecture du cache compare `now - at` à [ttl].
     */
    fun stamp(now: Long, ttl: Long, ok: Boolean): Long =
        if (ok) now else now - ttl + minOf(FAILURE_RETRY_MS, ttl)

    /** Programmes à garder : la réponse si elle existe ([fresh] non nul, même vide), sinon la dernière valable. */
    fun <T> keep(old: List<T>?, fresh: List<T>?): List<T> = fresh ?: old.orEmpty()
}

/**
 * Guide TV (Xtream).
 *  - « en ce moment / à suivre » : get_short_epg, cache mémoire de 5 minutes par chaîne ;
 *  - journée complète (grille « Guide », matchs du jour) : get_simple_data_table (repli get_short_epg&limit=30),
 *    cache mémoire de 30 minutes, au plus 6 requêtes simultanées.
 * Appels réseau : à faire hors du thread principal. Mode M3U : pas de guide (liste vide).
 */
object Epg {
    private const val TTL_MS = 5 * 60 * 1000L
    private const val DAY_TTL_MS = 30 * 60 * 1000L

    private class Entry(val at: Long, val programs: List<EpgProgram>)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val dayCache = ConcurrentHashMap<String, Entry>()

    /** Limite globale des requêtes « journée » (grille + matchs). */
    private val gate = Semaphore(6)

    /** Programmes en cours et suivants (au plus [limit]), triés ; liste vide si indisponible. */
    fun nowNext(ctx: Context, streamId: String, limit: Int = 3): List<EpgProgram> {
        val now = System.currentTimeMillis()
        cache[streamId]?.let { e ->
            if (now - e.at < TTL_MS) return e.programs.filter { it.endMs > now }.take(limit)
        }
        // La journée complète, si elle est déjà en cache, suffit.
        dayCache[streamId]?.let { e ->
            if (now - e.at < DAY_TTL_MS && e.programs.isNotEmpty()) {
                val next = e.programs.filter { it.endMs > now }
                if (next.size >= limit) return next.take(limit)
            }
        }
        val s = Settings(ctx)
        if (s.mode != Settings.MODE_XTREAM || streamId.isBlank()) return emptyList()
        val fresh = runCatching { fetch(s, "get_short_epg", streamId, limit + 2) }.getOrNull()
        // Échec réseau : on garde la dernière réponse valable, et on réessaie dans 30 s (pas 5 min).
        val programs = EpgCacheRules.keep(cache[streamId]?.programs, fresh)
        cache[streamId] = Entry(EpgCacheRules.stamp(now, TTL_MS, fresh != null), programs)
        return programs.filter { it.endMs > now }.take(limit)
    }

    /** Lecture seule du cache (affichage immédiat, sans réseau). */
    fun cached(streamId: String): List<EpgProgram> {
        val now = System.currentTimeMillis()
        val short = cache[streamId]?.programs?.filter { it.endMs > now }.orEmpty()
        if (short.isNotEmpty()) return short
        return dayCache[streamId]?.programs?.filter { it.endMs > now }.orEmpty()
    }

    /**
     * Tous les programmes connus en mémoire, y compris ceux déjà terminés (contrôle du direct : programme
     * regardé en différé). Triés, sans doublon de début.
     */
    fun cachedAll(streamId: String): List<EpgProgram> {
        val a = cache[streamId]?.programs.orEmpty()
        val b = dayCache[streamId]?.programs.orEmpty()
        if (b.isEmpty()) return a
        if (a.isEmpty()) return b
        return (a + b).sortedBy { it.startMs }.distinctBy { it.startMs }
    }

    /** Programme diffusé à l'instant [atMs] d'après le cache (null si inconnu). */
    fun cachedAt(streamId: String, atMs: Long): EpgProgram? =
        cachedAll(streamId).lastOrNull { atMs >= it.startMs && atMs < it.endMs }

    /** Journée en cache (null si absente ou périmée) : affichage immédiat de la grille. */
    fun cachedDay(streamId: String): List<EpgProgram>? {
        val e = dayCache[streamId] ?: return null
        return if (System.currentTimeMillis() - e.at < DAY_TTL_MS) e.programs else null
    }

    /**
     * Programmes de la journée (de 3 h avant à 30 h après maintenant), triés et sans chevauchement de doublons.
     * Suspendue, sur Dispatchers.IO, au plus 6 requêtes en parallèle ; annulable pendant l'attente.
     */
    suspend fun day(ctx: Context, streamId: String): List<EpgProgram> {
        cachedDay(streamId)?.let { return it }
        val s = Settings(ctx.applicationContext)
        if (s.mode != Settings.MODE_XTREAM || streamId.isBlank()) return emptyList()
        return gate.withPermit {
            cachedDay(streamId) ?: withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val table = runCatching { fetch(s, "get_simple_data_table", streamId, 0) }.getOrNull()?.let { window(it, now) }
                var fresh = table
                if (table == null || table.none { it.endMs > now }) {
                    val short = runCatching { fetch(s, "get_short_epg", streamId, 30) }.getOrNull()?.let { window(it, now) }
                    // Réponse valable (même vide) d'au moins une des deux requêtes = pas un échec.
                    fresh = if (short != null && (short.isNotEmpty() || table == null)) short else table
                }
                // Échec des deux requêtes : dernière journée valable gardée, nouvel essai dans 30 s.
                val old = dayCache[streamId]?.programs?.let { window(it, now) }
                val list = EpgCacheRules.keep(old, fresh)
                dayCache[streamId] = Entry(EpgCacheRules.stamp(now, DAY_TTL_MS, fresh != null), list)
                list
            }
        }
    }

    private fun window(list: List<EpgProgram>, now: Long): List<EpgProgram> {
        val from = now - 3 * 3600_000L
        val to = now + 30 * 3600_000L
        return list.asSequence()
            .filter { it.endMs > from && it.startMs < to && it.endMs > it.startMs }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
            .toList()
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun fetch(s: Settings, action: String, streamId: String, limit: Int): List<EpgProgram> {
        val base = XtreamClient.normalize(s.server)
        val url = "$base/player_api.php?username=${enc(s.user)}&password=${enc(s.pass)}" +
            "&action=$action&stream_id=${enc(streamId)}" + (if (limit > 0) "&limit=$limit" else "")
        val c = Http.open(url)
        try {
            JsonReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                r.isLenient = true
                val out = ArrayList<EpgProgram>()
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue()
                    return out
                }
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "epg_listings" && r.peek() == JsonToken.BEGIN_ARRAY) {
                        Json.forEachObject(r) { m ->
                            val start = m["start_timestamp"]?.toLongOrNull()?.times(1000) ?: return@forEachObject
                            val end = (m["stop_timestamp"] ?: m["end_timestamp"])?.toLongOrNull()?.times(1000)
                                ?: return@forEachObject
                            val title = b64(m["title"]) ?: return@forEachObject
                            out += EpgProgram(title, b64(m["description"]), start, end)
                        }
                    } else r.skipValue()
                }
                r.endObject()
                return out.sortedBy { it.startMs }
            }
        } finally {
            c.disconnect()
        }
    }

    /** Les panels Xtream encodent titres et descriptions en base64 (pas toujours). */
    private fun b64(v: String?): String? {
        if (v.isNullOrBlank()) return null
        val decoded = runCatching { String(Base64.decode(v, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        return decoded?.takeIf { it.isNotBlank() && it.none { ch -> ch == '�' } } ?: v
    }
}
