package com.fluxfr.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fluxfr.tv.tvhome.WatchNextSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class Progress(
    val key: String,
    val type: String, // "movie" ou "episode"
    val itemId: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val url: String?,
    val position: Long,
    val duration: Long,
    val updated: Long,
    val seriesId: String?,
    val season: Int,
    val episode: Int,
    /**
     * Position estimée (pourcentage × durée annoncée, ex. reprise venue d'ailleurs) : la durée réelle du fichier
     * peut différer, la reprise se fait alors au même POURCENTAGE ([ProgressStore.resumePosition]).
     * Remis à faux dès que le lecteur enregistre une vraie position.
     */
    val estimated: Boolean = false,
    /**
     * Origine d'une ligne importée ([NETFLIX]) ; null = regardé dans Prisme. Une lecture locale réécrit la
     * ligne sans origine.
     */
    val source: String? = null,
) {
    val finished get() = duration > 0 && position >= duration * 0.93
    val fraction get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    /** Position de reprise (ms) pour un fichier de durée réelle [actualDurationMs] (0 = inconnue). */
    fun resumeAt(actualDurationMs: Long): Long {
        if (finished) return 0
        val pos = if (estimated && actualDurationMs > 0 && duration > 0) (fraction.toDouble() * actualDurationMs).toLong() else position
        return if (pos > 10_000) pos else 0
    }

    companion object {
        const val MOVIE = "movie"
        const val EPISODE = "episode"
        /** [source] d'une ligne venue de l'historique Netflix ([NetflixImport]). */
        const val NETFLIX = "netflix"
        /** [source] d'une ligne venue de l'historique Prime Video ([NetflixImport], fichier du bouton d'export). */
        const val PRIME = "prime"
        /** Origines des lignes importées (retirées ensemble par « Annuler les imports »). */
        val IMPORTED_SOURCES = setOf(NETFLIX, PRIME)
        fun movieKey(id: String) = "movie:$id"
        fun episodeKey(id: String) = "ep:$id"
    }
}

/**
 * Positions de lecture (reprise des films et séries), stockées en SQLite, par profil.
 * Les méthodes sans paramètre `profile` portent sur le profil actif ([Profiles.current]).
 */
class ProgressStore private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "progress.db", null, 4) {

    /** Pour la rangée « Continuer à regarder » de Google TV ([WatchNextSync]). */
    private val appCtx: Context = ctx.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        createV2(db, "progress")
        addV3(db)
        addV4(db)
        db.execSQL("CREATE INDEX idx_series ON progress(profile_id, series_id)")
    }

    /** v4 : origine de la ligne (null = Prisme, "netflix" = historique importé). */
    private fun addV4(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE progress ADD COLUMN source TEXT")
    }

    /** v3 : position estimée (à reprendre au pourcentage). */
    private fun addV3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE progress ADD COLUMN estimated INTEGER NOT NULL DEFAULT 0")
    }

    private fun createV2(db: SQLiteDatabase, table: String) {
        db.execSQL(
            """CREATE TABLE $table(
                profile_id TEXT NOT NULL DEFAULT '${Profiles.MAIN}',
                key TEXT NOT NULL, type TEXT NOT NULL, item_id TEXT NOT NULL, title TEXT, subtitle TEXT,
                poster TEXT, url TEXT, position INTEGER, duration INTEGER, updated INTEGER,
                series_id TEXT, season INTEGER, episode INTEGER,
                PRIMARY KEY(profile_id, key))"""
        )
    }

    /** v1 → v2 : la clé primaire devient (profil, clé) ; l'historique existant va au profil Principal. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createV2(db, "progress_v2")
            db.execSQL(
                "INSERT OR REPLACE INTO progress_v2(profile_id, key, type, item_id, title, subtitle, poster, url, " +
                    "position, duration, updated, series_id, season, episode) SELECT '${Profiles.MAIN}', key, type, " +
                    "item_id, title, subtitle, poster, url, position, duration, updated, series_id, season, episode FROM progress"
            )
            db.execSQL("DROP TABLE progress")
            db.execSQL("ALTER TABLE progress_v2 RENAME TO progress")
            db.execSQL("DROP INDEX IF EXISTS idx_series")
            db.execSQL("CREATE INDEX idx_series ON progress(profile_id, series_id)")
        }
        if (oldVersion < 3) addV3(db)
        if (oldVersion < 4) addV4(db)
    }

    private val profile get() = Profiles.current.value

    fun save(p: Progress) = saveFor(profile, p)

    fun saveFor(profileId: String, p: Progress) {
        writableDatabase.insertWithOnConflict("progress", null, cv(profileId, p), SQLiteDatabase.CONFLICT_REPLACE)
        notifyChanged()
        runCatching { WatchNextSync.onSaved(appCtx, profileId, p) }
        // Google Drive : à envoyer à la prochaine synchro (fin de lecture, retour à l'accueil…).
        runCatching { DriveSync.markDirty(appCtx, profileId) }
    }

    private fun cv(profileId: String, p: Progress) = ContentValues().apply {
        put("profile_id", profileId)
        put("key", p.key); put("type", p.type); put("item_id", p.itemId); put("title", p.title)
        put("subtitle", p.subtitle); put("poster", p.poster); put("url", p.url)
        put("position", p.position); put("duration", p.duration); put("updated", p.updated)
        put("series_id", p.seriesId); put("season", p.season); put("episode", p.episode)
        put("estimated", if (p.estimated) 1 else 0)
        put("source", p.source)
    }

    /**
     * Lignes importées (historique Netflix) : écrites en une transaction, une ligne locale plus récente
     * (ou de même date : réimport du même fichier) n'est jamais remplacée ([ImportRules.shouldWrite]).
     * Renvoie le nombre de lignes écrites.
     */
    fun importRows(
        profileId: String,
        rows: List<Progress>,
        replaceSeries: Set<String> = emptySet(),
        source: String? = null,
    ): Int {
        if (rows.isEmpty()) return 0
        val db = writableDatabase
        val written = ArrayList<String>()
        var removed: List<String> = emptyList()
        db.beginTransaction()
        try {
            // Réimport : lignes importées de la même origine des séries [replaceSeries] remplacées (jamais
            // les lignes de Prisme ni celles d'une autre origine).
            val existing = query("SELECT * FROM progress WHERE profile_id = ?", arrayOf(profileId))
            val plan = ImportRules.reimportPlan(existing, rows, if (source != null) replaceSeries else emptySet(), source ?: "")
            for (k in plan.delete) db.delete("progress", "profile_id = ? AND key = ? AND source = ?", arrayOf(profileId, k, source ?: ""))
            for (p in plan.write) {
                db.insertWithOnConflict("progress", null, cv(profileId, p), SQLiteDatabase.CONFLICT_REPLACE)
                written += p.key
            }
            removed = plan.removed
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (removed.isNotEmpty()) {
            runCatching { DriveSync.onProgressRemoved(appCtx, profileId, removed) }
            if (written.isEmpty()) { notifyChanged(); watchNextChanged() }
        }
        if (written.isNotEmpty()) {
            notifyChanged()
            watchNextChanged()
            // Réimport après « Annuler l'import » : les suppressions de ces clés ne doivent pas l'emporter
            // (dates Netflix plus anciennes que la suppression) ici ni sur les autres TV.
            runCatching { DriveSync.onProgressRevived(appCtx, profileId, written) }
            runCatching { DriveSync.markDirty(appCtx, profileId) }
        }
        return written.size
    }

    /**
     * « Annuler les imports » : supprime les lignes du profil venues de [sources] (jamais celles de Prisme, ni une
     * ligne importée réécrite depuis par une lecture locale : son origine est alors nulle) ; les suppressions
     * partent vers les autres TV (Google Drive). Renvoie le nombre de lignes supprimées.
     */
    fun removeImported(profileId: String, sources: Set<String> = Progress.IMPORTED_SOURCES): Int {
        val rows = query("SELECT * FROM progress WHERE profile_id = ?", arrayOf(profileId))
        val keys = ImportRules.importedKeys(rows, sources)
        if (keys.isEmpty()) return 0
        val src = rows.associate { it.key to it.source }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (k in keys) db.delete("progress", "profile_id = ? AND key = ? AND source = ?", arrayOf(profileId, k, src[k] ?: continue))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        runCatching { DriveSync.onProgressRemoved(appCtx, profileId, keys) }
        notifyChanged()
        watchNextChanged()
        return keys.size
    }

    /** Nombre de lignes du profil venues de [source] (ex. titres importés depuis Netflix). */
    fun countImported(profileId: String, source: String = Progress.NETFLIX): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM progress WHERE profile_id = ? AND source = ?", arrayOf(profileId, source),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Résultat d'une synchro Google Drive ([DriveSync]) : écrit / supprime sans relancer de synchro. */
    internal fun applySynced(profileId: String, save: List<Progress>, delete: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (p in save) db.insertWithOnConflict("progress", null, cv(profileId, p), SQLiteDatabase.CONFLICT_REPLACE)
            for (k in delete) db.delete("progress", "profile_id = ? AND key = ?", arrayOf(profileId, k))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
        watchNextChanged()
    }

    private fun keysOf(where: String, args: Array<String>): List<String> =
        readableDatabase.rawQuery("SELECT key FROM progress WHERE $where", args).use { c ->
            val out = ArrayList<String>(c.count)
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    private fun tombstone(keys: List<String>) {
        runCatching { DriveSync.onProgressRemoved(appCtx, profile, keys) }
    }

    private fun watchNextChanged() {
        runCatching { WatchNextSync.onChanged(appCtx) }
    }

    fun get(key: String): Progress? = getFor(profile, key)

    /**
     * Position de reprise (ms, 0 = depuis le début) du profil actif pour [key], connaissant la durée réelle
     * du fichier ([actualDurationMs], 0 si pas encore connue). Une position estimée est reprise
     * au même pourcentage. À préférer à `get(key).position` dans le lecteur.
     */
    fun resumePosition(key: String, actualDurationMs: Long): Long = get(key)?.resumeAt(actualDurationMs) ?: 0

    fun getFor(profileId: String, key: String): Progress? =
        query("SELECT * FROM progress WHERE profile_id = ? AND key = ?", arrayOf(profileId, key)).firstOrNull()

    fun forSeries(seriesId: String): List<Progress> = forSeriesOf(profile, seriesId)

    fun forSeriesOf(profileId: String, seriesId: String): List<Progress> = query(
        "SELECT * FROM progress WHERE profile_id = ? AND series_id = ? ORDER BY updated DESC",
        arrayOf(profileId, seriesId),
    )

    fun movies(): Map<String, Progress> = query(
        "SELECT * FROM progress WHERE profile_id = ? AND type = ?", arrayOf(profile, Progress.MOVIE),
    ).associateBy { it.itemId }

    /** Tout l'historique d'un profil (films et épisodes), le plus récent d'abord : sert aux recommandations ([Reco]). */
    fun historyOf(profileId: String, limit: Int = 3000): List<Progress> = query(
        "SELECT * FROM progress WHERE profile_id = ? ORDER BY updated DESC LIMIT $limit", arrayOf(profileId),
    )

    /**
     * Films entamés + dernière série regardée (une entrée par série). Sans [includeFinished], une série terminée
     * (dernier épisode connu du catalogue vu, [ImportRules.seriesFinished]) n'y est pas : il n'y a pas
     * d'« Épisode suivant ». [includeFinished] : séries suivies, terminées comprises ([NewEpisodes]).
     */
    fun continueWatching(limit: Int = 25, includeFinished: Boolean = false): List<Progress> {
        // Compatibilité : tant que l'accueil n'appelle pas [onHomeShown], la synchro Drive « retour à l'accueil »
        // reste déclenchée ici (comportement historique). Dès le premier appel à onHomeShown, plus d'effet de bord.
        if (!homeHookAdopted) requestHomeSync(appCtx)
        return continueWatchingOf(profile, limit, includeFinished)
    }

    /**
     * « Continuer à regarder » du profil [profileId], SANS effet de bord (pas de synchro Drive). Lecture SQLite :
     * à appeler hors du fil principal (voir [continueWatchingAsync]).
     */
    fun continueWatchingOf(profileId: String, limit: Int = 25, includeFinished: Boolean = false): List<Progress> {
        val rows = query("SELECT * FROM progress WHERE profile_id = ? ORDER BY updated DESC LIMIT 400", arrayOf(profileId))
        val now = System.currentTimeMillis()
        if (includeFinished) return ImportRules.continueWatching(rows, now, limit)
        return ImportRules.continueWatching(rows, now, limit, finishedTest(profileId))
    }

    // ---------------------------------------------------------------- Accès hors du fil principal (interface)

    /** [continueWatching] sur Dispatchers.IO, sans synchro Drive (l'accueil appelle [onHomeShown] à part). */
    suspend fun continueWatchingAsync(limit: Int = 25, includeFinished: Boolean = false): List<Progress> {
        val p = profile
        return withContext(Dispatchers.IO) { continueWatchingOf(p, limit, includeFinished) }
    }

    /** [movies] sur Dispatchers.IO. */
    suspend fun moviesAsync(): Map<String, Progress> = withContext(Dispatchers.IO) { movies() }

    /** [forSeries] sur Dispatchers.IO. */
    suspend fun forSeriesAsync(seriesId: String): List<Progress> {
        val p = profile
        return withContext(Dispatchers.IO) { forSeriesOf(p, seriesId) }
    }

    /** [get] sur Dispatchers.IO. */
    suspend fun getAsync(key: String): Progress? {
        val p = profile
        return withContext(Dispatchers.IO) { getFor(p, key) }
    }

    /**
     * Flux « Continuer à regarder » : recalculé (sur Dispatchers.IO) à chaque changement du magasin ou de profil
     * ([version]). Pour Compose : `val rows by store.continueWatchingFlow().collectAsState(emptyList())`.
     */
    fun continueWatchingFlow(limit: Int = 25, includeFinished: Boolean = false): Flow<List<Progress>> =
        version.map { continueWatchingOf(profile, limit, includeFinished) }.flowOn(Dispatchers.IO)

    /** Flux des reprises de films (clé : id du film), recalculé à chaque changement. */
    fun moviesFlow(): Flow<Map<String, Progress>> = version.map { movies() }.flowOn(Dispatchers.IO)

    /**
     * Adresse de lecture retrouvée pour une ligne qui n'en a pas (reprise venue d'une autre TV par Google Drive) :
     * écrite sans changer la date ni relancer de synchro / Watch Next (l'adresse ne part jamais sur le Drive).
     */
    fun fillUrl(profileId: String, key: String, url: String): Boolean {
        val cv = ContentValues().apply { put("url", url) }
        return writableDatabase.update("progress", cv, "profile_id = ? AND key = ? AND url IS NULL", arrayOf(profileId, key)) > 0
    }

    /** La série de [newest] (sa ligne la plus récente) est-elle terminée pour [profileId] ? */
    fun isSeriesFinished(profileId: String, newest: Progress): Boolean = finishedTest(profileId)(newest)

    private fun finishedTest(profileId: String): (Progress) -> Boolean {
        val maxDone by lazy { finishedMax(profileId) }
        val last by lazy {
            val catalog = (CatalogRepository.state.value as? CatalogRepository.State.Ready)?.catalog
            runCatching { NewEpisodes.lastEpisodes(appCtx, profileId, catalog) }.getOrDefault { null }
        }
        return { p ->
            val sid = p.seriesId
            sid != null && p.type == Progress.EPISODE && p.finished && ImportRules.seriesFinished(p, maxDone[sid], last(sid))
        }
    }

    /** Par série : plus grand épisode terminé ([ImportRules.code]). */
    private fun finishedMax(profileId: String): Map<String, Int> =
        readableDatabase.rawQuery(
            "SELECT series_id, MAX(season * 10000 + episode) FROM progress WHERE profile_id = ? AND type = ? " +
                "AND series_id IS NOT NULL AND duration > 0 AND position >= duration * 0.93 GROUP BY series_id",
            arrayOf(profileId, Progress.EPISODE),
        ).use { c ->
            val out = HashMap<String, Int>()
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
            out
        }

    fun remove(p: Progress) {
        if (p.type == Progress.EPISODE && p.seriesId != null) {
            val keys = keysOf("profile_id = ? AND series_id = ?", arrayOf(profile, p.seriesId))
            writableDatabase.delete("progress", "profile_id = ? AND series_id = ?", arrayOf(profile, p.seriesId))
            // + marqueur de série : les épisodes vus sur une autre TV (inconnus ici) partent aussi.
            tombstone(keys + p.key + DriveMerge.seriesMarker(p.seriesId))
        } else {
            writableDatabase.delete("progress", "profile_id = ? AND key = ?", arrayOf(profile, p.key))
            tombstone(listOf(p.key))
        }
        notifyChanged()
        watchNextChanged()
    }

    /** Oublie la position enregistrée pour une seule clé (ex. « Depuis le début » d'un film). */
    fun reset(key: String) {
        val n = writableDatabase.delete("progress", "profile_id = ? AND key = ?", arrayOf(profile, key))
        if (n > 0) tombstone(listOf(key))
        notifyChanged()
        watchNextChanged()
    }

    /** Vide l'historique du profil actif. */
    fun clear() {
        // + marqueur « all » : les lignes venues d'autres TV (inconnues ici) partent aussi.
        tombstone(keysOf("profile_id = ?", arrayOf(profile)) + DriveMerge.ALL_MARKER)
        writableDatabase.delete("progress", "profile_id = ?", arrayOf(profile))
        notifyChanged()
        watchNextChanged()
    }

    /** Supprime tout l'historique d'un profil (suppression du profil). */
    fun deleteProfile(profileId: String) {
        writableDatabase.delete("progress", "profile_id = ?", arrayOf(profileId))
        notifyChanged()
        watchNextChanged()
    }

    private fun query(sql: String, args: Array<String>?): List<Progress> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Progress>(c.count)
            while (c.moveToNext()) out += c.toProgress()
            out
        }

    private fun Cursor.str(col: String): String? = getString(getColumnIndexOrThrow(col))
    private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))

    private fun Cursor.toProgress() = Progress(
        key = str("key")!!, type = str("type")!!, itemId = str("item_id")!!, title = str("title") ?: "",
        subtitle = str("subtitle"), poster = str("poster"), url = str("url"),
        position = long("position"), duration = long("duration"), updated = long("updated"),
        seriesId = str("series_id"), season = long("season").toInt(), episode = long("episode").toInt(),
        estimated = getColumnIndex("estimated").let { it >= 0 && getInt(it) != 0 },
        source = getColumnIndex("source").let { if (it >= 0 && !isNull(it)) getString(it) else null },
    )

    companion object {
        @Volatile private var instance: ProgressStore? = null
        fun get(ctx: Context): ProgressStore {
            Profiles.load(ctx)
            return instance ?: synchronized(this) {
                instance ?: ProgressStore(ctx.applicationContext).also { instance = it }
            }
        }

        private val _version = MutableStateFlow(0)
        /** Change à chaque sauvegarde (et changement de profil) : les écrans s'en servent pour se rafraîchir. */
        val version: StateFlow<Int> = _version.asStateFlow()

        @Volatile private var homeHookAdopted = false

        /**
         * À appeler par l'accueil quand il s'affiche (LaunchedEffect / ON_RESUME), PAS pendant la composition :
         * demande une synchro Google Drive en arrière-plan du profil actif (au plus toutes les 2 min). Ne bloque
         * pas. Dès le premier appel, [continueWatching] cesse de déclencher cette synchro lui-même.
         */
        fun onHomeShown(ctx: Context) {
            homeHookAdopted = true
            requestHomeSync(ctx)
        }

        private fun requestHomeSync(ctx: Context) {
            runCatching { DriveSync.requestSync(ctx.applicationContext, Profiles.current.value, minIntervalMs = 2 * 60_000L) }
        }

        fun notifyChanged() {
            _version.update { it + 1 } // atomique : appelé depuis plusieurs fils
            // Changement de profil (Profiles.set appelle notifyChanged) : la rangée Watch Next suit le profil actif.
            instance?.let { runCatching { WatchNextSync.onProfileMaybeChanged(it.appCtx) } }
        }
    }
}

/** Règles pures (testées) : fusion des lignes importées et rangée « Continuer à regarder ». */
internal object ImportRules {
    /** Au-delà, un titre dont la dernière ligne est un import ne remonte plus dans « Continuer à regarder ». */
    const val IMPORT_MAX_AGE_MS = 45L * 24 * 3600 * 1000

    /** Une ligne importée n'écrase jamais une ligne existante plus récente ou de même date (réimport). */
    fun shouldWrite(existing: Progress?, incoming: Progress): Boolean =
        existing == null || existing.updated < incoming.updated

    /** Clés à supprimer pour annuler un import : les lignes dont l'origine est [source], et elles seules. */
    fun importedKeys(rows: List<Progress>, source: String): List<String> = importedKeys(rows, setOf(source))

    /** Clés à supprimer pour « Annuler les imports » : les lignes dont l'origine est dans [sources]. */
    fun importedKeys(rows: List<Progress>, sources: Set<String>): List<String> =
        rows.filter { it.source != null && it.source in sources }.map { it.key }

    /** Réimport : [delete] lignes à supprimer d'abord, [write] lignes à écrire ensuite. */
    data class ReimportPlan(val delete: List<String>, val write: List<Progress>) {
        /** Lignes supprimées et non réécrites (suppressions à envoyer aux autres TV). */
        val removed: List<String> get() = write.map { it.key }.toSet().let { w -> delete.filter { it !in w } }
    }

    /**
     * Réimport : les épisodes importés de [source] des séries [seriesIds] sont supprimés puis remplacés par
     * [incoming] (une erreur de saison / numéro d'un import précédent disparaît) ; les lignes de Prisme (origine
     * nulle) et celles d'une autre origine restent, et une ligne restante plus récente n'est pas écrasée ([shouldWrite]).
     */
    fun reimportPlan(existing: List<Progress>, incoming: List<Progress>, seriesIds: Set<String>, source: String): ReimportPlan {
        val delete = existing.filter {
            it.type == Progress.EPISODE && it.source != null && it.source == source && it.seriesId != null && it.seriesId in seriesIds
        }.map { it.key }
        val gone = delete.toSet()
        val cur = existing.filter { it.key !in gone }.associateBy { it.key }
        return ReimportPlan(delete, incoming.filter { shouldWrite(cur[it.key], it) })
    }

    /** Rang d'un épisode (saison, numéro) pour les comparaisons. */
    fun code(season: Int, episode: Int) = season * 10_000 + episode

    /**
     * Série terminée : sa ligne la plus récente [newest] est un épisode fini, et le plus grand épisode fini de la
     * série ([maxWatched], [code]) atteint le dernier épisode connu du catalogue ([last]). Inconnu : non terminée.
     */
    fun seriesFinished(newest: Progress, maxWatched: Int?, last: Int?): Boolean {
        if (newest.type != Progress.EPISODE || !newest.finished || last == null || last <= 0) return false
        return maxOf(maxWatched ?: 0, code(newest.season, newest.episode)) >= last
    }

    private fun staleImport(p: Progress, now: Long) =
        p.source != null && p.source in Progress.IMPORTED_SOURCES && now - p.updated > IMPORT_MAX_AGE_MS

    /**
     * Films entamés + dernière série regardée (une entrée par série), lignes triées de la plus récente à la
     * plus ancienne. Un film / une série dont la ligne la plus récente est un import de plus de 45 jours est
     * ignoré(e) ici (il / elle compte toujours comme vu(e) ailleurs).
     */
    fun continueWatching(
        rows: List<Progress>,
        now: Long,
        limit: Int,
        finished: (Progress) -> Boolean = { false },
    ): List<Progress> {
        val seenSeries = HashSet<String>()
        val out = ArrayList<Progress>()
        for (p in rows) {
            when (p.type) {
                Progress.MOVIE -> if (p.position > 30_000 && !p.finished && !staleImport(p, now)) out += p
                Progress.EPISODE ->
                    if (p.seriesId != null && seenSeries.add(p.seriesId) && !staleImport(p, now) && !finished(p)) out += p
            }
            if (out.size >= limit) break
        }
        return out
    }
}
