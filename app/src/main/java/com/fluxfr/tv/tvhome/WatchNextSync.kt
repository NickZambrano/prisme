package com.fluxfr.tv.tvhome

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.fluxfr.tv.DeepLinks
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.Profiles
import com.fluxfr.tv.data.Progress
import com.fluxfr.tv.data.ProgressStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Rangée système « Continuer à regarder » (Watch Next) de Google TV, tenue à jour depuis
 * [ProgressStore] pour le profil actif.
 *
 * Une entrée par « emplacement » : un film (clé de reprise) ou une série (« series:<id> » : l'épisode en
 * cours, ou l'épisode suivant quand le dernier est terminé). Correspondance emplacement → id du programme
 * gardée dans les SharedPreferences « watchnext ». Toutes les écritures passent par un fil dédié, et chaque
 * appel au fournisseur est protégé (certains appareils n'ont pas le fournisseur TV).
 */
object WatchNextSync {
    private const val TAG = "WatchNext"
    private const val PREFS = "watchnext"
    private const val MAP = "m:"       // m:<emplacement> → "<programId>|<signature>"
    // Retraits par l'utilisateur : « dp:<profil>|<emplacement> » → heure, pour CE profil seulement
    // ([WatchNextRules.dismissKey]). Anciennes clés « d:<emplacement> » : migrées au premier passage.
    private const val MAX_ITEMS = 12
    private const val THROTTLE_MS = 30_000L

    private val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "watchnext").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null
    private var pendingAt = 0L
    /** Dernière écriture par emplacement (horloge SystemClock.elapsedRealtime). */
    private val lastPush = ConcurrentHashMap<String, Long>()
    @Volatile private var syncedProfile: String? = null

    // ---------------------------------------------------------------- déclencheurs

    /** Après une sauvegarde de position : au plus une mise à jour toutes les 30 s par élément (sauf fin). */
    fun onSaved(ctx: Context, profileId: String, p: Progress) {
        if (!supported || profileId != Profiles.current.value) return
        val last = lastPush[slotOf(p)]
        val now = SystemClock.elapsedRealtime()
        val delay = if (last == null || p.finished) 1_500L else maxOf(1_500L, THROTTLE_MS - (now - last))
        schedule(ctx, delay)
    }

    /** Suppression / remise à zéro / historique vidé. */
    fun onChanged(ctx: Context) = schedule(ctx, 500)

    /** Appelé à chaque notification du magasin : resynchronise si le profil actif a changé. */
    fun onProfileMaybeChanged(ctx: Context) {
        if (supported && syncedProfile != null && syncedProfile != Profiles.current.value) schedule(ctx, 300)
    }

    /** Synchronisation complète (démarrage de l'appli, ACTION_INITIALIZE_PROGRAMS). */
    fun requestSync(ctx: Context, delayMs: Long = 0) = schedule(ctx, delayMs)

    /** Le système (ou l'utilisateur) a retiré un programme de la rangée : on l'oublie, sans le republier. */
    fun onProgramRemoved(ctx: Context, programId: Long) {
        if (!supported || programId <= 0) return
        val app = ctx.applicationContext
        exec.execute {
            runCatching {
                val prefs = prefs(app)
                val e = prefs.edit()
                // La rangée publiée est celle du dernier profil synchronisé : le retrait ne vaut que pour lui.
                val owner = syncedProfile ?: Profiles.current.value
                for ((k, v) in prefs.all) {
                    if (k.startsWith(MAP) && (v as? String)?.substringBefore('|')?.toLongOrNull() == programId) {
                        e.remove(k).putLong(WatchNextRules.dismissKey(owner, k.removePrefix(MAP)), System.currentTimeMillis())
                    }
                }
                e.apply()
                deleteProgram(app, programId)
            }.onFailure { Log.w(TAG, "remove", it) }
        }
    }

    @Synchronized
    private fun schedule(ctx: Context, delayMs: Long) {
        if (!supported) return
        val app = ctx.applicationContext
        val at = SystemClock.elapsedRealtime() + delayMs
        val p = pending
        if (p != null && !p.isDone && pendingAt <= at) return // une synchro plus proche est déjà prévue
        p?.cancel(false)
        pendingAt = at
        pending = exec.schedule({ runCatching { syncNow(app) }.onFailure { Log.w(TAG, "sync", it) } }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Exécute une synchronisation tout de suite sur le fil dédié, puis [done] (pour goAsync des récepteurs). */
    fun syncThen(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        exec.execute {
            try { if (supported) syncNow(app) } catch (t: Throwable) { Log.w(TAG, "sync", t) } finally { done() }
        }
    }

    // ---------------------------------------------------------------- synchronisation

    private data class Entry(val slot: String, val program: WatchNextProgram, val signature: String)

    private fun slotOf(p: Progress) =
        if (p.type == Progress.EPISODE && p.seriesId != null) "series:${p.seriesId}" else p.key

    private fun syncNow(ctx: Context) {
        Profiles.load(ctx)
        val profile = Profiles.current.value
        val store = ProgressStore.get(ctx)
        val prefs = prefs(ctx)
        // Anciens retraits sans profil (s'appliquaient à tous les profils) : rattachés au profil synchronisé
        // ici (la rangée affichée est toujours celle du profil actif), puis effacés.
        val legacy = prefs.all.filterKeys { WatchNextRules.legacyDismissSlot(it) != null }
        if (legacy.isNotEmpty()) {
            val m = prefs.edit()
            for ((k, v) in legacy) {
                val newKey = WatchNextRules.dismissKey(profile, WatchNextRules.legacyDismissSlot(k)!!)
                if (!prefs.contains(newKey) && v is Long) m.putLong(newKey, v)
                m.remove(k)
            }
            m.apply()
        }
        // Sans effet de bord (pas de synchro Drive depuis ce fil).
        val rows = store.continueWatchingOf(profile, MAX_ITEMS)
        val catalog = lazy { CatalogAccess.get(ctx) }

        val desired = LinkedHashMap<String, Entry>()
        for (p in rows) {
            val slot = slotOf(p)
            val dismissedAt = prefs.getLong(WatchNextRules.dismissKey(profile, slot), 0L)
            if (dismissedAt > 0 && p.updated <= dismissedAt) continue
            runCatching { buildEntry(ctx, store, p, slot, profile) { catalog.value } }
                .onFailure { Log.w(TAG, "entry", it) }
                .getOrNull()?.let { desired[slot] = it }
        }

        val e = prefs.edit()
        // Retire les programmes qui ne sont plus voulus (terminés, supprimés, autre profil…).
        val mapped = prefs.all.filterKeys { it.startsWith(MAP) }
        val keptIds = HashSet<Long>()
        for ((k, v) in mapped) {
            val slot = k.removePrefix(MAP)
            val id = (v as? String)?.substringBefore('|')?.toLongOrNull()
            if (slot !in desired) {
                if (id != null) deleteProgram(ctx, id)
                e.remove(k)
                lastPush.remove(slot)
            } else if (id != null) keptIds += id
        }
        // Publie ou met à jour.
        for (entry in desired.values) {
            val old = prefs.getString(MAP + entry.slot, null)
            val oldId = old?.substringBefore('|')?.toLongOrNull()
            if (oldId != null && old!!.substringAfter('|') == entry.signature) continue
            val cv = entry.program.toContentValues()
            var id = oldId
            if (oldId != null) {
                val n = runCatching {
                    ctx.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(oldId), cv, null, null)
                }.getOrDefault(0)
                if (n < 1) id = null // retiré par le système entre-temps : on le recrée
            }
            if (id == null) {
                id = runCatching {
                    ctx.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, cv)
                        ?.let { ContentUris.parseId(it) }
                }.onFailure { Log.w(TAG, "insert", it) }.getOrNull()
            }
            if (id != null && id > 0) {
                e.putString(MAP + entry.slot, "$id|${entry.signature}")
                keptIds += id
                lastPush[entry.slot] = SystemClock.elapsedRealtime()
            } else {
                e.remove(MAP + entry.slot)
            }
        }
        e.apply()
        cleanOrphans(ctx, keptIds)
        syncedProfile = profile
    }

    private fun buildEntry(
        ctx: Context, store: ProgressStore, p: Progress, slot: String, profile: String, catalog: () -> Catalog?,
    ): Entry? {
        val now = System.currentTimeMillis()
        val engaged = if (p.updated > 0) p.updated else now
        return when (p.type) {
            Progress.MOVIE -> {
                if (p.finished || p.position <= 0) return null
                val b = WatchNextProgram.Builder()
                b.setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setType(TvContractCompat.PreviewProgramColumns.TYPE_MOVIE)
                    .setTitle(p.title)
                    .setLastEngagementTimeUtcMillis(engaged)
                    .setInternalProviderId(p.key)
                    .setIntentUri(DeepLinks.playUri(p.key, profile))
                applyTimes(b, p.position, p.duration)
                p.poster?.let { b.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3) }
                Entry(slot, b.build(), "C|${p.key}|${p.position / 1000}|${p.duration / 1000}|${p.updated}|${p.poster}")
            }
            Progress.EPISODE -> {
                if (!p.finished) {
                    val c = catalog()
                    var ep = c?.let { CatalogAccess.seriesOfEpisode(it, p.seriesId, p.itemId) }
                        ?.episodes?.firstOrNull { it.id == p.itemId }
                    // Reprise venue d'une autre TV (Google Drive : sans adresse) : épisode retrouvé sur le serveur
                    // (get_series_info) et adresse notée dans la ligne, pour que la tuile se lise directement.
                    if (p.url == null) {
                        if (ep == null) ep = SeriesEpisodes.episodes(ctx, p.seriesId, c)?.let { WatchNextRules.find(it, p.itemId) }
                        ep?.url?.takeIf { it.isNotBlank() }?.let { url -> runCatching { store.fillUrl(profile, p.key, url) } }
                    }
                    val b = episodeBuilder(p.title, p.season, p.episode, ep?.title, p.poster ?: ep?.image, p.key, profile, engaged)
                    b.setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    applyTimes(b, p.position, p.duration)
                    Entry(slot, b.build(), "C|${p.key}|${p.position / 1000}|${p.duration / 1000}|${p.updated}|${ep?.title}")
                } else {
                    // Épisode terminé : on propose le suivant (catalogue M3U, sinon épisodes du serveur Xtream).
                    val c = catalog()
                    val s = c?.let { CatalogAccess.seriesOfEpisode(it, p.seriesId, p.itemId) }
                    if (s != null) {
                        val next = CatalogAccess.nextEpisode(s, p.itemId) ?: return null
                        val key = Progress.episodeKey(next.id)
                        val b = episodeBuilder(s.name, next.season, next.number, next.title, s.poster ?: next.image, key, profile, engaged)
                        b.setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
                        if (next.durationSec > 0) b.setDurationMillis((next.durationSec * 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        return Entry(slot, b.build(), "N|$key|${p.updated}|${next.title}")
                    }
                    // Xtream : les épisodes ne sont pas dans le catalogue. Épisode suivant d'après le serveur ;
                    // la tuile ouvre la fiche de la série (la lecture directe d'un épisode jamais commencé
                    // n'est possible que pour les épisodes du catalogue).
                    val seriesId = p.seriesId ?: return null
                    val eps = SeriesEpisodes.episodes(ctx, seriesId, c) ?: return null
                    val next = WatchNextRules.next(eps, p.itemId, p.season, p.episode) ?: return null
                    val series = c?.seriesById?.get(seriesId)
                    val key = Progress.episodeKey(next.id)
                    val b = episodeBuilder(
                        series?.name ?: p.title, next.season, next.number, next.title,
                        series?.poster ?: p.poster ?: next.image, key, profile, engaged,
                    )
                    b.setIntentUri(DeepLinks.seriesUri(seriesId))
                    b.setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
                    if (next.durationSec > 0) b.setDurationMillis((next.durationSec * 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    Entry(slot, b.build(), "S|$key|${p.updated}|${next.title}")
                }
            }
            else -> null
        }
    }

    private fun episodeBuilder(
        seriesName: String, season: Int, number: Int, episodeTitle: String?, poster: String?,
        key: String, profile: String, engaged: Long,
    ): WatchNextProgram.Builder {
        val b = WatchNextProgram.Builder()
        b.setType(TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE)
            .setTitle(seriesName)
            .setLastEngagementTimeUtcMillis(engaged)
            .setInternalProviderId(key)
            .setIntentUri(DeepLinks.playUri(key, profile))
        if (season > 0) b.setSeasonNumber(season)
        if (number > 0) b.setEpisodeNumber(number)
        val epTitle = episodeTitle?.trim().orEmpty()
        b.setEpisodeTitle(epTitle.ifEmpty { "Épisode $number" })
        poster?.let { b.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3) }
        return b
    }

    private fun applyTimes(b: WatchNextProgram.Builder, position: Long, duration: Long) {
        if (duration > 0) b.setDurationMillis(duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (position > 0) b.setLastPlaybackPositionMillis(position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    // ---------------------------------------------------------------- fournisseur

    private fun deleteProgram(ctx: Context, id: Long) {
        runCatching { ctx.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) }
            .onFailure { Log.w(TAG, "delete", it) }
    }

    /** Programmes de l'appli inconnus de la correspondance (données effacées, ancien profil…) : supprimés. */
    @SuppressLint("RestrictedApi")
    private fun cleanOrphans(ctx: Context, kept: Set<Long>) {
        runCatching {
            val ids = ArrayList<Long>()
            ctx.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI, arrayOf(TvContractCompat.BaseTvColumns._ID), null, null, null,
            )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
            for (id in ids) if (id !in kept) deleteProgram(ctx, id)
        }.onFailure { Log.w(TAG, "orphans", it) }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
