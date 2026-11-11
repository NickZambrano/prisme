package com.fluxfr.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Badge « NOUVEL ÉPISODE » des séries suivies par le profil (reprise en cours ou « Ma liste »).
 *
 * Pour chaque série, on mémorise (par profil, préférences "fluxfr", clé "seenEpisodes_<profil>")
 * la dernière « signature » connue de la série — date last_modified de get_series (Xtream) ou nombre
 * d'épisodes (M3U) — et, si on l'a vu, le dernier épisode (saison, numéro) à cette date.
 * Une série est « nouvelle » quand le catalogue montre une signature plus récente ET que le profil
 * avait vu le dernier épisode connu. Ouvrir la fiche marque la série comme vue.
 * Premier passage (série jamais mémorisée) : on enregistre en silence, sans badge.
 *
 * Accès disque / SQLite : à appeler hors du thread principal.
 */
object NewEpisodes {
    private const val PREFS = "fluxfr"
    private const val KEY = "seenEpisodes"

    private class Seen(val sig: Long, val season: Int, val episode: Int) {
        val latestKnown get() = season > 0 || episode > 0
    }

    private val _version = MutableStateFlow(0)
    /** Change quand une série est marquée vue : les écrans recalculent leurs badges. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val lock = Any()

    /** Signature de la série dans le catalogue (plus grande = plus récente) ; 0 si inconnue. */
    fun signature(s: Series): Long {
        val eps = s.episodes
        if (!eps.isNullOrEmpty()) return eps.size.toLong()
        return s.added.coerceAtLeast(0L)
    }

    private fun prefKey(profile: String) = KEY + "_" + profile

    private fun read(ctx: Context, profile: String): HashMap<String, Seen> {
        val raw = prefs(ctx).getString(prefKey(profile), null) ?: return HashMap()
        val out = HashMap<String, Seen>()
        for (line in raw.split('\n')) {
            val p = line.split('\t')
            if (p.size < 2) continue
            val sig = p[1].toLongOrNull() ?: continue
            out[p[0]] = Seen(sig, p.getOrNull(2)?.toIntOrNull() ?: 0, p.getOrNull(3)?.toIntOrNull() ?: 0)
        }
        return out
    }

    private fun write(ctx: Context, profile: String, map: Map<String, Seen>) {
        val s = map.entries.joinToString("\n") { (id, v) -> "$id\t${v.sig}\t${v.season}\t${v.episode}" }
        prefs(ctx).edit().putString(prefKey(profile), s).apply()
    }

    /**
     * Identifiants des séries suivies qui ont un nouvel épisode. Enregistre en silence les séries
     * suivies encore inconnues.
     */
    fun compute(ctx: Context, catalog: Catalog, profile: String, favorites: Collection<String>): Set<String> {
        val store = ProgressStore.get(ctx)
        val followed = LinkedHashSet<String>()
        // Séries terminées comprises : un nouvel épisode / une nouvelle saison doit y être signalé(e).
        for (p in store.continueWatching(100, includeFinished = true)) {
            if (p.type == Progress.EPISODE) p.seriesId?.let(followed::add)
        }
        val favSeries = favorites.filter { it.startsWith("series:") }.map { it.substringAfter(':') }.toSet()
        followed += favSeries
        if (followed.isEmpty()) return emptySet()

        val byId = catalog.unfiltered.seriesById
        val out = HashSet<String>()
        synchronized(lock) {
            val seen = read(ctx, profile)
            var changed = false
            for (id in followed) {
                val series = byId[id] ?: continue
                val sig = signature(series)
                if (sig <= 0L) continue
                val stored = seen[id]
                if (stored == null) {
                    // Premier passage : on mémorise sans afficher de badge.
                    seen[id] = Seen(sig, 0, 0)
                    changed = true
                    continue
                }
                if (sig <= stored.sig) continue
                val progress = store.forSeriesOf(profile, id)
                val sawLatest = if (stored.latestKnown) {
                    progress.any { compareValues(it.season * 10_000 + it.episode, stored.season * 10_000 + stored.episode) >= 0 }
                } else {
                    progress.isNotEmpty() || id in favSeries
                }
                if (sawLatest) out += id
            }
            if (changed) write(ctx, profile, seen)
        }
        return out
    }

    /**
     * La fiche de la série a été ouverte : sa signature actuelle (et son dernier épisode, si les
     * épisodes sont chargés) devient la référence. Supprime le badge.
     */
    fun markSeen(ctx: Context, profile: String, series: Series, episodes: List<Episode>?) {
        val sig = signature(series)
        if (sig <= 0L) return
        val last = episodes?.maxWithOrNull(compareBy({ it.season }, { it.number }))
        synchronized(lock) {
            val seen = read(ctx, profile)
            val prev = seen[series.id]
            val season = last?.season ?: prev?.takeIf { it.sig == sig }?.season ?: 0
            val episode = last?.number ?: prev?.takeIf { it.sig == sig }?.episode ?: 0
            if (prev != null && prev.sig == sig && prev.season == season && prev.episode == episode) return
            seen[series.id] = Seen(maxOf(sig, prev?.sig ?: 0L), season, episode)
            write(ctx, profile, seen)
        }
        _version.value++
    }

    /**
     * Dernier épisode connu de chaque série ([ImportRules.code]) pour [profile] : d'après les épisodes du
     * catalogue [catalog] s'ils sont chargés (M3U), sinon le dernier épisode mémorisé à l'ouverture de la fiche
     * ou à l'import ([markSeen]) — oublié dès que le catalogue montre une signature plus récente (nouvel
     * épisode ou nouvelle saison : la série n'est plus terminée). Null si inconnu.
     */
    fun lastEpisodes(ctx: Context, profile: String, catalog: Catalog?): (String) -> Int? {
        val seen = synchronized(lock) { read(ctx, profile) }
        val byId = catalog?.unfiltered?.seriesById
        return { id ->
            val series = byId?.get(id)
            val eps = series?.episodes
            if (!eps.isNullOrEmpty()) eps.maxOf { ImportRules.code(it.season, it.number) }
            else seen[id]?.takeIf { it.latestKnown && (series == null || signature(series) <= it.sig) }
                ?.let { ImportRules.code(it.season, it.episode) }
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
