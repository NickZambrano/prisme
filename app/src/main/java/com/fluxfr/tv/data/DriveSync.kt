package com.fluxfr.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

// ============================================================================ Document synchronisé (pur, testé)

/**
 * Contenu du fichier « Prisme – sync.json » (schéma v1). Les adresses de lecture ([Progress.url]) ne sont
 * JAMAIS écrites : elles peuvent contenir les identifiants IPTV.
 */
data class DriveDoc(
    val progress: Map<String, Progress> = emptyMap(),
    /** Reprises supprimées : clé → date de suppression (ms). */
    val progressDeleted: Map<String, Long> = emptyMap(),
    /** « Ma liste » (films et séries) : clé → date d'ajout (ms ; 0 = inconnue, ajout antérieur à la synchro). */
    val favorites: Map<String, Long> = emptyMap(),
    val favoritesDeleted: Map<String, Long> = emptyMap(),
    val deviceName: String = "",
    val savedAt: Long = 0,
    val schema: Int = SCHEMA,
    /**
     * Reprises recréées après une suppression (réimport Netflix après « Annuler l'import ») : clé → date de
     * recréation (ms). Une ligne recréée après la suppression reste en vie même si sa date (`updated`, date de
     * visionnage Netflix) est plus ancienne que la suppression. Champ facultatif (absent si vide).
     */
    val progressRevived: Map<String, Long> = emptyMap(),
) {
    companion object {
        const val SCHEMA = 1
    }
}

object DriveCodec {
    fun rowMap(p: Progress): Map<String, Any?> = linkedMapOf(
        "key" to p.key, "type" to p.type, "itemId" to p.itemId, "title" to p.title, "subtitle" to p.subtitle,
        "poster" to p.poster, "position" to p.position, "duration" to p.duration, "updated" to p.updated,
        "seriesId" to p.seriesId, "season" to p.season, "episode" to p.episode,
        "finished" to p.finished, "estimated" to p.estimated,
        // Pas de "url" : elle peut contenir l'utilisateur et le mot de passe IPTV.
    ).apply {
        // Origine (ex. import Netflix) : seulement si renseignée, le format reste celui des anciennes versions.
        p.source?.let { put("source", it) }
    }

    fun rowOf(o: Any?): Progress? {
        val m = o as? Map<*, *> ?: return null
        fun s(k: String) = m[k] as? String
        fun l(k: String) = (m[k] as? Number)?.toLong() ?: 0L
        return Progress(
            key = s("key") ?: return null, type = s("type") ?: return null, itemId = s("itemId") ?: return null,
            title = s("title") ?: "", subtitle = s("subtitle"), poster = s("poster"), url = null,
            position = l("position"), duration = l("duration"), updated = l("updated"),
            seriesId = s("seriesId"), season = l("season").toInt(), episode = l("episode").toInt(),
            estimated = m["estimated"] as? Boolean ?: false,
            source = s("source"),
        )
    }

    private fun stamps(m: Map<String, Long>, k: String, ts: String) =
        m.entries.sortedBy { it.key }.map { linkedMapOf("key" to it.key, ts to it.value) }

    private fun stampsOf(o: Any?, ts: String): Map<String, Long> = (o as? List<*>).orEmpty().mapNotNull { e ->
        val m = e as? Map<*, *> ?: return@mapNotNull null
        val k = m["key"] as? String ?: return@mapNotNull null
        k to ((m[ts] as? Number)?.toLong() ?: 0L)
    }.toMap()

    /** Partie comparable du document (sans l'appareil ni la date d'enregistrement), en ordre stable. */
    fun body(d: DriveDoc): Map<String, Any?> = linkedMapOf<String, Any?>(
        "progress" to d.progress.values.sortedBy { it.key }.map(::rowMap),
        "progressDeleted" to stamps(d.progressDeleted, "key", "deletedAt"),
        "favorites" to stamps(d.favorites, "key", "addedAt"),
        "favoritesDeleted" to stamps(d.favoritesDeleted, "key", "deletedAt"),
    ).apply {
        // Seulement si renseigné : le format reste celui des anciennes versions.
        if (d.progressRevived.isNotEmpty()) put("progressRevived", stamps(d.progressRevived, "key", "revivedAt"))
    }

    fun encode(d: DriveDoc): String = MiniJson.write(
        linkedMapOf<String, Any?>("app" to "Prisme", "schema" to d.schema, "deviceName" to d.deviceName, "savedAt" to d.savedAt) + body(d)
    )

    fun decode(text: String): DriveDoc {
        val m = MiniJson.parse(text) as Map<*, *>
        return DriveDoc(
            progress = (m["progress"] as? List<*>).orEmpty().mapNotNull(::rowOf).associateBy { it.key },
            progressDeleted = stampsOf(m["progressDeleted"], "deletedAt"),
            favorites = stampsOf(m["favorites"], "addedAt"),
            favoritesDeleted = stampsOf(m["favoritesDeleted"], "deletedAt"),
            deviceName = m["deviceName"] as? String ?: "",
            savedAt = (m["savedAt"] as? Number)?.toLong() ?: 0L,
            schema = (m["schema"] as? Number)?.toInt() ?: 1,
            progressRevived = stampsOf(m["progressRevived"], "revivedAt"),
        )
    }

    fun sameContent(a: DriveDoc, b: DriveDoc) = MiniJson.write(body(a)) == MiniJson.write(body(b))
}

object DriveMerge {
    const val MAX_ROWS = 2000
    const val MAX_TOMBSTONES = 3000
    const val TOMBSTONE_TTL_MS = 60L * 24 * 3600 * 1000

    /**
     * Ordre total entre deux versions d'une même reprise : la plus récente gagne ; à date égale, critères
     * déterministes (identiques sur toutes les TV) pour ne jamais faire de ping-pong.
     */
    private val rowOrder: Comparator<Progress> = compareBy<Progress>(
        { it.updated }, { it.position }, { it.duration }, { if (it.estimated) 0 else 1 },
        { it.title }, { it.subtitle ?: "" }, { it.poster ?: "" }, { it.seriesId ?: "" }, { it.season }, { it.episode },
        { it.itemId }, { it.type },
    )

    fun newer(a: Progress?, b: Progress?): Progress? = when {
        a == null -> b
        b == null -> a
        rowOrder.compare(a, b) >= 0 -> a
        else -> b
    }

    private fun maxStamps(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
        val out = HashMap(a)
        for ((k, v) in b) if (v > (out[k] ?: Long.MIN_VALUE)) out[k] = v
        return out
    }

    private fun pruneTombs(t: Map<String, Long>, now: Long): Map<String, Long> =
        t.filterValues { now - it < TOMBSTONE_TTL_MS }.entries.sortedByDescending { it.value }
            .take(MAX_TOMBSTONES).associate { it.key to it.value }

    /**
     * Fusion par clé : la version la plus récente (`updated`) gagne ; une suppression gagne sur toute version
     * qui n'est pas plus récente qu'elle. Suppressions gardées 60 jours ; 2000 reprises au plus (les plus récentes).
     * Exception : une ligne recréée ([DriveDoc.progressRevived]) après la suppression gagne, quelle que soit
     * sa date (réimport Netflix après annulation : dates de visionnage anciennes).
     * Le résultat ne garde pas les adresses de lecture.
     */
    /**
     * Marqueurs de suppression globaux, rangés avec les suppressions de reprises ([DriveDoc.progressDeleted]) :
     * « series:<id> » (série retirée : toute ligne de cette série datée d'avant est supprimée, y compris les
     * épisodes vus sur une autre TV) et « all » (historique vidé : toute ligne datée d'avant). Une activité
     * plus récente revit normalement. Les anciennes versions les gardent comme des suppressions sans effet.
     */
    const val SERIES_MARKER = "series:"
    const val ALL_MARKER = "all"

    fun seriesMarker(seriesId: String) = SERIES_MARKER + seriesId

    fun isMarker(key: String) = key == ALL_MARKER || key.startsWith(SERIES_MARKER)

    /** Date du marqueur (série ou « all ») qui couvre [p], ou null. */
    fun markerFor(p: Progress, deleted: Map<String, Long>): Long? {
        val all = deleted[ALL_MARKER]
        val series = if (p.type == Progress.EPISODE && p.seriesId != null) deleted[seriesMarker(p.seriesId)] else null
        return when {
            all == null -> series
            series == null -> all
            else -> maxOf(all, series)
        }
    }

    /** [p] est-elle effacée par un marqueur (date `updated` antérieure ou égale) ? */
    fun coveredByMarker(p: Progress, deleted: Map<String, Long>): Boolean =
        markerFor(p, deleted)?.let { p.updated <= it } ?: false

    fun merge(local: DriveDoc, remote: DriveDoc, now: Long): DriveDoc {
        val delP = maxStamps(local.progressDeleted, remote.progressDeleted)
        val revP = maxStamps(local.progressRevived, remote.progressRevived)
        val rows = HashMap<String, Progress>()
        val tombP = HashMap<String, Long>()
        val revived = HashMap<String, Long>()
        for (k in local.progress.keys + remote.progress.keys + delP.keys) {
            val best = newer(local.progress[k], remote.progress[k])
            val keyDel = delP[k]
            val marker = best?.let { markerFor(it, delP) }
            val del = if (keyDel == null) marker else if (marker == null) keyDel else maxOf(keyDel, marker)
            val rev = revP[k]
            val revivedAfterDelete = del != null && rev != null && rev > del
            if (best != null && (del == null || best.updated > del || revivedAfterDelete)) {
                rows[k] = best.copy(url = null)
                // Gardée tant qu'une suppression plus ancienne circule encore (sur une autre TV).
                if (revivedAfterDelete) revived[k] = rev!!
            } else if (keyDel != null) tombP[k] = keyDel
        }
        val keptRows = rows.values.sortedByDescending { it.updated }.take(MAX_ROWS).associateBy { it.key }

        val delF = maxStamps(local.favoritesDeleted, remote.favoritesDeleted)
        val addF = maxStamps(local.favorites, remote.favorites)
        val favs = HashMap<String, Long>()
        val tombF = HashMap<String, Long>()
        for (k in addF.keys + delF.keys) {
            val added = addF[k]
            val del = delF[k]
            if (added != null && (del == null || added > del)) favs[k] = added
            else if (del != null) tombF[k] = del
        }
        return DriveDoc(
            progress = keptRows,
            progressDeleted = pruneTombs(tombP, now),
            favorites = favs,
            favoritesDeleted = pruneTombs(tombF, now),
            deviceName = local.deviceName,
            savedAt = now,
            progressRevived = pruneTombs(revived.filterKeys { it in keptRows }, now),
        )
    }

    /**
     * Suppressions / recréations locales après la recréation de [keys] à [now] (réimport) : leurs suppressions
     * sont oubliées et une date de recréation est notée, pour l'emporter sur les suppressions encore présentes
     * sur le Drive ou sur les autres TV.
     */
    fun revive(
        deleted: Map<String, Long>,
        revived: Map<String, Long>,
        keys: Collection<String>,
        now: Long,
    ): Pair<Map<String, Long>, Map<String, Long>> = (deleted - keys.toSet()) to (revived + keys.associateWith { now })

    /** Ce que la fusion change sur cette TV. */
    class LocalChanges(val save: List<Progress>, val delete: Set<String>, val favorites: List<String>?)

    /**
     * Lignes à écrire / supprimer localement et nouvelle « Ma liste » (null si inchangée).
     * [localFavOrder] : ordre actuel des films / séries de « Ma liste » (le plus récent d'abord).
     */
    fun localChanges(local: DriveDoc, merged: DriveDoc, localFavOrder: List<String>): LocalChanges {
        val save = ArrayList<Progress>()
        for ((k, r) in merged.progress) {
            val l = local.progress[k]
            if (l == null || (l.copy(url = null) != r && newer(l, r) === r)) save += r.copy(url = l?.url)
        }
        val delete = local.progress.filterTo(HashMap()) { (k, r) ->
            k !in merged.progress && (k in merged.progressDeleted || coveredByMarker(r, merged.progressDeleted))
        }.keys.toHashSet()
        val kept = localFavOrder.filter { it in merged.favorites }
        val fresh = merged.favorites.keys.filter { it !in localFavOrder }.sortedByDescending { merged.favorites[it] }
        val favs = fresh + kept
        return LocalChanges(save, delete, if (favs == localFavOrder) null else favs)
    }
}

/** Règles pures (testées) de la synchro : drapeau « à envoyer », un compte Google par profil. */
object DriveSyncRules {
    /** Le drapeau « à envoyer » peut être effacé seulement si aucune modification n'a eu lieu depuis [snapshot]. */
    fun canClearDirty(snapshot: Long, current: Long): Boolean = snapshot == current

    /** Un profil lié : [linkedAt] = date de liaison (0 = ancienne), [order] = rang du profil (départage). */
    data class Link(val profile: String, val name: String, val email: String?, val linkedAt: Long, val order: Int)

    private fun norm(email: String?) = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Premier profil de [links] lié au compte [email] (casse ignorée ; adresse vide : jamais). */
    fun sameAccount(email: String?, links: List<Link>): Link? {
        val e = norm(email) ?: return null
        return links.firstOrNull { norm(it.email) == e }
    }

    /** Profil lié AVANT [me] (date, puis rang) au même compte Google ; null si [me] est le premier ou seul. */
    fun earlierSameAccount(me: Link, links: List<Link>): Link? {
        val e = norm(me.email) ?: return null
        val order = compareBy<Link>({ it.linkedAt }, { it.order }, { it.profile })
        return links.filter { it.profile != me.profile && norm(it.email) == e && order.compare(it, me) < 0 }
            .minWithOrNull(order)
    }
}

// ============================================================================ Synchronisation Google Drive

/**
 * Synchronisation du suivi de chaque profil lié à un compte Google (reprises, épisodes vus, « Ma liste »)
 * via un fichier « Prisme – sync.json » dans le dossier « Prisme » du Drive de l'utilisateur (portée
 * drive.file : l'appli ne voit que les fichiers qu'elle a créés). Tout se passe sur Dispatchers.IO ;
 * les erreurs réseau sont silencieuses (seul l'état affiché dans le panneau Google change).
 */
object DriveSync {
    const val FILE_NAME = "Prisme – sync.json"
    const val FOLDER_NAME = "Prisme"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private const val PREFS = "drive_sync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val lastRun = HashMap<String, Long>()
    private val pending = HashMap<String, Job>()

    /** Profil → message d'état (« Synchronisé », « Hors ligne »…). */
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()
    /** Profil → date de la dernière synchro réussie. */
    private val _lastSync = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastSync: StateFlow<Map<String, Long>> = _lastSync.asStateFlow()
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    fun isFavKey(k: String) = k.startsWith("movie:") || k.startsWith("series:")

    // ------------------------------------------------------------------ État local (suppressions, dates d'ajout)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readStamps(ctx: Context, key: String): Map<String, Long> {
        val raw = prefs(ctx).getString(key, null) ?: return emptyMap()
        return runCatching {
            (MiniJson.parse(raw) as Map<*, *>).entries.mapNotNull { (k, v) ->
                (k as? String)?.let { it to ((v as? Number)?.toLong() ?: 0L) }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeStamps(ctx: Context, key: String, m: Map<String, Long>) {
        prefs(ctx).edit().putString(key, MiniJson.write(m)).apply()
    }

    private val stampLock = Any()

    private fun updateStamps(ctx: Context, key: String, fn: (MutableMap<String, Long>) -> Unit) = synchronized(stampLock) {
        val m = readStamps(ctx, key).toMutableMap()
        fn(m)
        val now = System.currentTimeMillis()
        val pruned = m.filterValues { it == 0L || now - it < DriveMerge.TOMBSTONE_TTL_MS }
            .entries.sortedByDescending { it.value }.take(DriveMerge.MAX_TOMBSTONES).associate { it.key to it.value }
        writeStamps(ctx, key, pruned)
    }

    /**
     * Compteur de modifications locales par profil : une synchro note sa valeur AVANT de lire l'état local et
     * n'efface le drapeau « à envoyer » que s'il n'a pas bougé ; sinon une nouvelle passe est programmée
     * (sinon une modification faite pendant la synchro serait perdue).
     */
    private val changes = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    private fun changeCounter(profile: String) = changes.getOrPut(profile) { java.util.concurrent.atomic.AtomicLong() }

    fun markDirty(ctx: Context, profile: String) {
        val counter = changeCounter(profile)
        synchronized(counter) {
            counter.incrementAndGet()
            val p = prefs(ctx)
            if (!p.getBoolean("dirty_$profile", false)) p.edit().putBoolean("dirty_$profile", true).apply()
        }
    }

    /**
     * Fin d'envoi : efface « à envoyer » seulement si rien n'a changé depuis [snapshot] (atomique avec
     * [markDirty]) ; renvoie false s'il faut une nouvelle passe.
     */
    private fun finishUpload(ctx: Context, profile: String, snapshot: Long, version: String?): Boolean {
        val counter = changeCounter(profile)
        synchronized(counter) {
            val clean = DriveSyncRules.canClearDirty(snapshot, counter.get())
            val e = prefs(ctx).edit().putString("version_$profile", version)
            if (clean) e.putBoolean("dirty_$profile", false)
            e.apply()
            return clean
        }
    }

    /** Reprises supprimées sur cette TV (suppression, « Depuis le début », historique vidé). */
    fun onProgressRemoved(ctx: Context, profile: String, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val now = System.currentTimeMillis()
        updateStamps(ctx, "progdel_$profile") { m -> keys.forEach { m[it] = now } }
        markDirty(ctx, profile)
        requestSync(ctx, profile, minIntervalMs = 0, delayMs = 10_000)
    }

    /**
     * Reprises recréées sur cette TV après une suppression (réimport Netflix après « Annuler l'import ») :
     * leurs suppressions locales sont oubliées et la recréation datée, pour qu'une suppression plus ancienne
     * encore présente sur le Drive ou sur une autre TV ne les efface pas ([DriveMerge.merge]).
     */
    fun onProgressRevived(ctx: Context, profile: String, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(stampLock) {
            val (dels, revs) = DriveMerge.revive(
                readStamps(ctx, "progdel_$profile"), readStamps(ctx, "progrev_$profile"), keys, now,
            )
            writeStamps(ctx, "progdel_$profile", dels)
            writeStamps(ctx, "progrev_$profile", revs.filterValues { now - it < DriveMerge.TOMBSTONE_TTL_MS })
        }
        markDirty(ctx, profile)
    }

    /** « Ma liste » modifiée sur cette TV (ajout / retrait). */
    fun onFavoritesChanged(ctx: Context, profile: String, added: Collection<String>, removed: Collection<String>) {
        val a = added.filter(::isFavKey)
        val r = removed.filter(::isFavKey)
        if (a.isEmpty() && r.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(stampLock) {
            val adds = readStamps(ctx, "favts_$profile").toMutableMap()
            val dels = readStamps(ctx, "favdel_$profile").toMutableMap()
            a.forEach { adds[it] = now; dels.remove(it) }
            r.forEach { adds.remove(it); dels[it] = now }
            writeStamps(ctx, "favts_$profile", adds)
            writeStamps(ctx, "favdel_$profile", dels.filterValues { now - it < DriveMerge.TOMBSTONE_TTL_MS })
        }
        markDirty(ctx, profile)
        requestSync(ctx, profile, minIntervalMs = 0, delayMs = 10_000)
    }

    // ------------------------------------------------------------------ Déclencheurs

    /**
     * Synchro en arrière-plan d'un profil lié à Google, au plus une fois par [minIntervalMs], après [delayMs]
     * (une nouvelle demande retardée remplace la précédente). Ne bloque jamais : appelable du fil principal.
     */
    fun requestSync(
        ctx: Context,
        profile: String = Profiles.current.value,
        minIntervalMs: Long = 0,
        delayMs: Long = 0,
    ) {
        val app = ctx.applicationContext
        if (!GoogleAuth.isLinked(app, profile)) return
        if (delayMs > 0) {
            synchronized(pending) {
                pending.remove(profile)?.cancel()
                pending[profile] = scope.launch {
                    delay(delayMs)
                    synchronized(pending) { pending.remove(profile) }
                    if (claim(profile, minIntervalMs)) runCatching { sync(app, profile) }
                }
            }
            return
        }
        if (!claim(profile, minIntervalMs)) return
        scope.launch { runCatching { sync(app, profile) } }
    }

    /** Synchro complète immédiate (connexion, bouton « Synchroniser maintenant ») ; ne bloque pas. */
    fun syncNow(ctx: Context, profile: String) {
        val app = ctx.applicationContext
        if (!GoogleAuth.isLinked(app, profile)) return
        claim(profile, 0)
        _busy.value = _busy.value + profile
        scope.launch {
            try { runCatching { sync(app, profile, force = true) } } finally { _busy.value = _busy.value - profile }
        }
    }

    private fun claim(profile: String, minIntervalMs: Long): Boolean {
        val now = System.currentTimeMillis()
        synchronized(lastRun) {
            if (minIntervalMs > 0 && now - (lastRun[profile] ?: 0) < minIntervalMs) return false
            lastRun[profile] = now
            return true
        }
    }

    fun statusFor(ctx: Context, profile: String): String? =
        _status.value[profile] ?: prefs(ctx).getString("status_$profile", null)

    fun lastSyncFor(ctx: Context, profile: String): Long =
        _lastSync.value[profile] ?: prefs(ctx).getLong("last_$profile", 0L)

    private fun setStatus(ctx: Context, profile: String, s: String) {
        _status.value = _status.value + (profile to s)
        prefs(ctx).edit().putString("status_$profile", s).apply()
    }

    /** Profil délié (déconnexion ou accès révoqué) : oublie le fichier Drive, garde l'historique des suppressions. */
    fun forget(ctx: Context, profile: String, status: String? = null) {
        prefs(ctx).edit().remove("file_$profile").remove("version_$profile").remove("last_$profile")
            .remove("linkacct_$profile").remove("linked_$profile").apply()
        _lastSync.value = _lastSync.value - profile
        if (status != null) setStatus(ctx, profile, status)
        else {
            _status.value = _status.value - profile
            prefs(ctx).edit().remove("status_$profile").apply()
        }
    }

    /** Relie / délie : appelé par le panneau de connexion. */
    fun disconnect(ctx: Context, profile: String) {
        GoogleAuth.disconnect(ctx, profile)
        forget(ctx, profile)
    }

    // ------------------------------------------------------------------ Synchro

    private class Remote(val fileId: String, val doc: DriveDoc?, val etag: String?, val version: String?)

    private class Session(val ctx: Context, val profile: String) {
        var revoked = false
        fun token(force: Boolean = false): String =
            GoogleAuth.validToken(ctx, profile, force) { revoked = true } ?: throw IOException("non lié")

        /** Requête Drive authentifiée ; réessaie une fois avec un nouveau jeton après un 401. */
        fun call(
            method: String, url: String, body: ByteArray? = null, type: String? = null,
            headers: Map<String, String> = emptyMap(),
        ): GoogleHttp.Response {
            val r = GoogleHttp.request(method, url, body, type, token(), headers)
            if (r.code != 401) return r
            return GoogleHttp.request(method, url, body, type, token(force = true), headers)
        }
    }

    private class DriveHttpError(val code: Int) : IOException("Drive : HTTP $code")

    private fun q(s: String) = GoogleHttp.enc(s)

    private fun meta(s: Session, fileId: String): Map<*, *>? {
        val r = s.call("GET", "$API/$fileId?fields=id,version,trashed")
        if (r.code == 404) return null
        if (r.code !in 200..299) throw DriveHttpError(r.code)
        val m = MiniJson.parse(r.body) as? Map<*, *> ?: return null
        return if (m["trashed"] == true) null else m
    }

    private fun findFile(s: Session): String? {
        val query = "name='${FILE_NAME.replace("'", "\\'")}' and trashed=false"
        val r = s.call("GET", "$API?q=${q(query)}&spaces=drive&orderBy=modifiedTime%20desc&pageSize=10&fields=files(id)")
        if (r.code !in 200..299) throw DriveHttpError(r.code)
        val files = (MiniJson.parse(r.body) as? Map<*, *>)?.get("files") as? List<*>
        return (files?.firstOrNull() as? Map<*, *>)?.get("id") as? String
    }

    private fun folderId(s: Session): String {
        val query = "name='$FOLDER_NAME' and mimeType='$FOLDER_MIME' and trashed=false"
        val r = s.call("GET", "$API?q=${q(query)}&spaces=drive&pageSize=10&fields=files(id)")
        if (r.code !in 200..299) throw DriveHttpError(r.code)
        val files = (MiniJson.parse(r.body) as? Map<*, *>)?.get("files") as? List<*>
        ((files?.firstOrNull() as? Map<*, *>)?.get("id") as? String)?.let { return it }
        val c = s.call(
            "POST", "$API?fields=id",
            MiniJson.write(mapOf("name" to FOLDER_NAME, "mimeType" to FOLDER_MIME)).toByteArray(Charsets.UTF_8),
            "application/json; charset=UTF-8",
        )
        if (c.code !in 200..299) throw DriveHttpError(c.code)
        return (MiniJson.parse(c.body) as Map<*, *>)["id"] as String
    }

    private fun download(s: Session, fileId: String, version: String?): Remote? {
        val r = s.call("GET", "$API/$fileId?alt=media")
        if (r.code == 404) return null
        if (r.code !in 200..299) throw DriveHttpError(r.code)
        val doc = if (r.body.isBlank()) DriveDoc() else DriveCodec.decode(r.body)
        return Remote(fileId, doc, r.etag, version)
    }

    /** Fichier distant : retrouvé par son id mémorisé, sinon par son nom ; null s'il n'existe pas encore. */
    private fun fetchRemote(ctx: Context, s: Session): Remote? {
        val p = prefs(ctx)
        var id = p.getString("file_${s.profile}", null)
        var m = id?.let { meta(s, it) }
        if (m == null) {
            id = findFile(s) ?: return null
            m = meta(s, id) ?: return null
            p.edit().putString("file_${s.profile}", id).apply()
        }
        return download(s, id!!, m["version"]?.toString())
    }

    private fun create(s: Session, json: String): Pair<String, String?> {
        val parent = folderId(s)
        val boundary = "prisme" + System.nanoTime()
        val metaJson = MiniJson.write(mapOf("name" to FILE_NAME, "parents" to listOf(parent), "mimeType" to "application/json"))
        val body = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metaJson\r\n" +
            "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$json\r\n--$boundary--\r\n")
            .toByteArray(Charsets.UTF_8)
        val r = s.call("POST", "$UPLOAD?uploadType=multipart&fields=id,version", body, "multipart/related; boundary=$boundary")
        if (r.code !in 200..299) throw DriveHttpError(r.code)
        val m = MiniJson.parse(r.body) as Map<*, *>
        return (m["id"] as String) to m["version"]?.toString()
    }

    /** Mise à jour ; null sur conflit (412 : le fichier a changé depuis la lecture). */
    private fun update(s: Session, fileId: String, json: String, etag: String?): String? {
        val headers = if (etag != null) mapOf("If-Match" to etag) else emptyMap()
        val r = s.call(
            "PATCH", "$UPLOAD/$fileId?uploadType=media&fields=id,version",
            json.toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8", headers,
        )
        if (r.code == 412 || (etag != null && r.code == 400)) return null
        if (r.code !in 200..299) throw DriveHttpError(r.code)
        return (MiniJson.parse(r.body) as? Map<*, *>)?.get("version")?.toString() ?: ""
    }

    /** État local du profil, sous la forme du document partagé. */
    private fun localDoc(ctx: Context, profile: String): Pair<DriveDoc, List<String>> {
        val rows = ProgressStore.get(ctx).historyOf(profile, DriveMerge.MAX_ROWS)
        val favOrder = Favorites.listFor(ctx, profile).filter(::isFavKey)
        val stamps = readStamps(ctx, "favts_$profile")
        return DriveDoc(
            progress = rows.associateBy { it.key },
            progressDeleted = readStamps(ctx, "progdel_$profile"),
            favorites = favOrder.associateWith { stamps[it] ?: 0L },
            favoritesDeleted = readStamps(ctx, "favdel_$profile"),
            deviceName = runCatching { PrismeNode.deviceName(ctx) }.getOrDefault("TV"),
            progressRevived = readStamps(ctx, "progrev_$profile"),
        ) to favOrder
    }

    private suspend fun sync(ctx: Context, profile: String, force: Boolean = false) = mutex.withLock {
        if (!GoogleAuth.isLinked(ctx, profile)) return@withLock
        // Même compte Google qu'un profil lié AVANT celui-ci sur cette TV : les deux historiques se
        // mélangeraient dans le même fichier Drive. On ne synchronise pas ce profil.
        sharedAccountOwner(ctx, profile)?.let { other ->
            setStatus(ctx, profile, sharedAccountMessage(other))
            return@withLock
        }
        _busy.value = _busy.value + profile
        val s = Session(ctx, profile)
        var again = false
        try {
            val p = prefs(ctx)
            val dirty = p.getBoolean("dirty_$profile", false)
            // Rien de neuf ici ni sur le Drive (même version) : on s'arrête là (une petite requête).
            val knownId = p.getString("file_$profile", null)
            val knownVersion = p.getString("version_$profile", null)
            if (!force && !dirty && knownId != null && knownVersion != null) {
                val m = meta(s, knownId)
                if (m != null && m["version"]?.toString() == knownVersion) {
                    done(ctx, profile)
                    return@withLock
                }
            }
            var attempt = 0
            while (true) {
                val remote = fetchRemote(ctx, s)
                if (remote?.doc != null && remote.doc.schema > DriveDoc.SCHEMA) {
                    setStatus(ctx, profile, "Fichier créé par une version plus récente de Prisme : mets l'appli à jour")
                    return@withLock
                }
                // Noté AVANT de lire l'état local : toute modification ultérieure garde le profil « à envoyer ».
                val snapshot = changeCounter(profile).get()
                val (local, favOrder) = localDoc(ctx, profile)
                val now = System.currentTimeMillis()
                val merged = DriveMerge.merge(local, remote?.doc ?: DriveDoc(), now)
                applyLocal(ctx, profile, local, merged, favOrder)
                val needUpload = remote?.doc == null || !DriveCodec.sameContent(merged, remote.doc)
                var version = remote?.version
                if (needUpload) {
                    val json = DriveCodec.encode(merged)
                    if (remote == null) {
                        val (id, v) = create(s, json)
                        p.edit().putString("file_$profile", id).apply()
                        version = v
                    } else {
                        // If-Match seulement au premier essai : si Google le refuse à tort, le second passe sans.
                        val v = update(s, remote.fileId, json, if (attempt == 0) remote.etag else null)
                        if (v == null) {
                            if (attempt++ == 0) continue // conflit : relecture, nouvelle fusion, un seul nouvel essai
                            throw DriveHttpError(412)
                        }
                        version = v
                    }
                }
                // Modifié pendant la synchro : reste « à envoyer », et une nouvelle passe est programmée.
                again = !finishUpload(ctx, profile, snapshot, version)
                done(ctx, profile)
                return@withLock
            }
        } catch (e: Exception) {
            if (s.revoked) {
                forget(ctx, profile, "Accès Google retiré : reconnecte le profil")
            } else {
                setStatus(ctx, profile, if (e is DriveHttpError && (e.code == 403 || e.code == 401))
                    "Échec : accès Google Drive refusé" else "Échec : hors ligne ou Google injoignable")
            }
        } finally {
            _busy.value = _busy.value - profile
            if (again) requestSync(ctx, profile, minIntervalMs = 0, delayMs = AGAIN_DELAY_MS)
        }
    }

    private const val AGAIN_DELAY_MS = 5_000L

    // ------------------------------------------------------------------ Un compte Google par profil

    private fun sharedAccountMessage(otherName: String) =
        "Ce compte Google est déjà utilisé par le profil « $otherName ». Chaque profil doit avoir son propre compte."

    /**
     * Avant de lier un compte Google au profil [exceptProfile] (le profil en cours de liaison) : nom d'un AUTRE
     * profil de cette TV déjà lié au même compte ([email], casse ignorée), sinon null. Si non null, l'interface
     * doit refuser la liaison et afficher [sharedAccountRefusal].
     */
    fun accountAlreadyLinked(ctx: Context, email: String, exceptProfile: String? = null): String? {
        val app = ctx.applicationContext
        Profiles.load(app)
        val links = Profiles.list.value.filter { it.id != exceptProfile && GoogleAuth.isLinked(app, it.id) }
            .map { DriveSyncRules.Link(it.id, it.name, GoogleAuth.email(app, it.id), 0L, 0) }
        return DriveSyncRules.sameAccount(email, links)?.name
    }

    /** Message à afficher quand [accountAlreadyLinked] a trouvé le profil [otherName]. */
    fun sharedAccountRefusal(otherName: String): String = sharedAccountMessage(otherName)

    /**
     * Profil lié AVANT [profile] au même compte Google (nom), ou null. Date de liaison notée par cette classe
     * (« linked_<profil> », « linkacct_<profil> ») ; les liaisons antérieures à cette version comptent comme
     * les plus anciennes, départagées par l'ordre des profils.
     */
    private fun sharedAccountOwner(ctx: Context, profile: String): String? {
        val app = ctx.applicationContext
        Profiles.load(app)
        val profiles = Profiles.list.value
        val links = profiles.mapIndexedNotNull { i, pr ->
            if (!GoogleAuth.isLinked(app, pr.id)) return@mapIndexedNotNull null
            val email = GoogleAuth.email(app, pr.id)
            DriveSyncRules.Link(pr.id, pr.name, email, linkedAt(app, pr.id, email), i)
        }
        val me = links.firstOrNull { it.profile == profile } ?: return null
        return DriveSyncRules.earlierSameAccount(me, links)?.name
    }

    /**
     * Date de liaison du profil au compte [email] : notée la première fois qu'on voit ce compte pour ce profil.
     * 0 = liaison antérieure à cette version (profil déjà synchronisé, ou vu pour la première fois sans compte noté).
     */
    private fun linkedAt(ctx: Context, profile: String, email: String?): Long {
        val p = prefs(ctx)
        val key = email?.trim()?.lowercase().orEmpty()
        val known = p.getString("linkacct_$profile", null)
        if (known == key) return p.getLong("linked_$profile", 0L)
        // Jamais vu (mise à jour) mais déjà synchronisé avec ce compte : liaison ancienne.
        val legacy = known == null && (p.contains("file_$profile") || p.contains("last_$profile"))
        val at = if (legacy) 0L else System.currentTimeMillis()
        p.edit().putString("linkacct_$profile", key).putLong("linked_$profile", at).apply()
        return at
    }

    private fun done(ctx: Context, profile: String) {
        val now = System.currentTimeMillis()
        _lastSync.value = _lastSync.value + (profile to now)
        prefs(ctx).edit().putLong("last_$profile", now).apply()
        setStatus(ctx, profile, "Synchronisé")
    }

    /** Applique sur cette TV ce que la fusion a changé, sans redéclencher de synchro. */
    private fun applyLocal(ctx: Context, profile: String, local: DriveDoc, merged: DriveDoc, favOrder: List<String>) {
        val ch = DriveMerge.localChanges(local, merged, favOrder)
        val catalog = (CatalogRepository.state.value as? CatalogRepository.State.Ready)?.catalog?.unfiltered
        // Adresse de lecture jamais transmise : retrouvée dans le catalogue de cette TV (films).
        val save = ch.save.map { r ->
            if (r.url == null && r.type == Progress.MOVIE) r.copy(url = catalog?.movieById?.get(r.itemId)?.url) else r
        }
        // Union avec l'état actuel (au plus récent) : une suppression faite pendant la synchro n'est pas perdue.
        fun keepMax(m: MutableMap<String, Long>, add: Map<String, Long>) {
            for ((k, v) in add) if (v > (m[k] ?: Long.MIN_VALUE)) m[k] = v
        }
        updateStamps(ctx, "progdel_$profile") { m ->
            keepMax(m, merged.progressDeleted)
            // Suppressions battues par une recréation (réimport sur une autre TV) : plus utiles ici.
            merged.progressRevived.forEach { (k, rev) -> if ((m[k] ?: Long.MAX_VALUE) < rev) m.remove(k) }
        }
        updateStamps(ctx, "progrev_$profile") { keepMax(it, merged.progressRevived) }
        updateStamps(ctx, "favdel_$profile") { keepMax(it, merged.favoritesDeleted) }
        updateStamps(ctx, "favts_$profile") { keepMax(it, merged.favorites) }
        if (save.isNotEmpty() || ch.delete.isNotEmpty()) ProgressStore.get(ctx).applySynced(profile, save, ch.delete)
        val favs = ch.favorites
        if (favs != null) Favorites.applySynced(ctx, profile) { cur -> favs + cur.filterNot(::isFavKey) }
    }
}
