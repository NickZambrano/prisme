package com.fluxfr.tv.data

import android.content.Context
import android.util.Log
import com.fluxfr.tv.BuildConfig
import com.fluxfr.tv.data.ContentFilter.Companion.cleanCategory
import com.fluxfr.tv.data.ContentFilter.Companion.cleanName
import com.fluxfr.tv.data.ContentFilter.Companion.dedupKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.exp

object CatalogRepository {
    sealed interface State {
        data object Idle : State
        data class Loading(val message: String, val progress: Float = 0f, val detail: String? = null) : State
        /**
         * [missing] : catégories que le serveur a refusé de donner au dernier rafraîchissement (trop de connexions
         * simultanées, 403 / 429, délai dépassé…), au format « Films › Action ». Leur contenu précédent (cache) est
         * repris quand il existe ; un nouvel essai est prévu (15 min, et au prochain démarrage). Vide = complet.
         * L'interface peut l'afficher discrètement (non bloquant).
         */
        data class Ready(
            val catalog: Catalog,
            val refreshing: Boolean = false,
            val progress: Float = 0f,
            val missing: List<String> = emptyList(),
        ) : State {
            val incomplete: Boolean get() = missing.isNotEmpty()
        }
        data class Failed(val message: String) : State
    }

    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
    /** Catalogue incomplet : nouvel essai automatique après ce délai. */
    private const val PARTIAL_RETRY_MS = 15 * 60 * 1000L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val mutex = Mutex()

    /**
     * Génération du compte : incrémentée par [clear] (déconnexion) et quand le compte IPTV change. Un chargement
     * commencé sous une ancienne génération ne publie plus rien et n'écrit plus le cache ([publish]).
     */
    private val genLock = Any()
    @Volatile private var generation = 0
    /** Génération du catalogue actuellement publié (Ready) ; -1 = aucun. */
    private var readyGeneration = -1
    private var currentAccount: String? = null
    @Volatile private var retryJob: Job? = null

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "catalog.json")

    /** Publie [s] seulement si [gen] est toujours la génération courante (sinon : compte changé, rien n'est affiché). */
    private fun publish(gen: Int, s: State): Boolean = synchronized(genLock) {
        if (gen != generation) return false
        _state.value = s
        readyGeneration = if (s is State.Ready) gen else -1
        true
    }

    private fun isCurrent(gen: Int) = gen == generation

    /** Écrit le cache seulement pour la génération courante ; un cache écrit pendant une déconnexion est effacé. */
    private fun writeCache(ctx: Context, gen: Int, catalog: Catalog, account: String?, missing: List<String>) {
        if (!isCurrent(gen)) return
        val file = cacheFile(ctx)
        runCatching { CatalogCache.write(file, catalog, account, missing) }
        if (!isCurrent(gen)) file.delete()
    }

    /** Identifiant (empreinte, sans mot de passe) du compte IPTV configuré : le cache d'un autre compte est ignoré. */
    internal fun accountKey(s: Settings): String = CatalogFetchRules.accountKey(
        m3u = s.mode == Settings.MODE_M3U, m3uUrl = s.m3uUrl, server = s.server, user = s.user,
    )

    /**
     * Affiche le cache tout de suite, puis rafraîchit depuis le serveur s'il a plus de 12 h, s'il est incomplet,
     * ou si [force]. Pendant un rafraîchissement, l'appli reste utilisable (fine barre de progression en haut).
     */
    suspend fun load(ctx: Context, force: Boolean = false) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val app = ctx.applicationContext
                val settings = Settings(ctx)
                Http.userAgent = settings.userAgent
                val file = cacheFile(ctx)
                val account = accountKey(settings)
                val gen: Int
                val memory: State.Ready?
                synchronized(genLock) {
                    // Autre compte que celui du catalogue en mémoire : nouvelle génération, jamais de repli sur
                    // l'ancien catalogue (ni en mémoire, ni via un chargement encore en cours).
                    if (currentAccount != null && currentAccount != account) {
                        generation++
                        readyGeneration = -1
                        episodeCache.clear()
                    }
                    currentAccount = account
                    gen = generation
                    memory = (_state.value as? State.Ready)?.takeIf { readyGeneration == gen }
                }
                retryJob?.cancel()
                val disk = if (memory == null) runCatching { CatalogCache.readEntry(file, account) }.getOrNull() else null
                val cached = memory?.catalog ?: disk?.catalog
                val cachedMissing = memory?.missing ?: disk?.missing.orEmpty()
                if (cached != null) {
                    if (!publish(gen, State.Ready(cached, missing = cachedMissing))) return@withLock
                    if (!CatalogFetchRules.needsRefresh(cached.fetchedAt, System.currentTimeMillis(), MAX_AGE_MS, cachedMissing.isNotEmpty(), force)) {
                        startEnrichment(ctx, settings, gen) // genres des films pas encore analysés
                        return@withLock
                    }
                    publish(gen, State.Ready(cached, refreshing = true, missing = cachedMissing))
                } else {
                    publish(gen, State.Loading("Connexion au serveur…", 0.02f))
                }
                try {
                    val filter = ContentFilter(settings)
                    val tracker = Tracker(cached, gen)
                    val missing = ArrayList<String>()
                    val catalog = if (settings.mode == Settings.MODE_M3U) fromM3u(settings, filter, tracker)
                    else fromXtream(ctx, settings, filter, tracker, cached?.unfiltered, missing)
                    if (!isCurrent(gen)) return@withLock // déconnexion / changement de compte pendant le chargement
                    if (missing.isNotEmpty()) {
                        Log.w("Catalog", "Catalogue incomplet, nouvel essai dans 15 min : $missing")
                        // Incomplet : la date du précédent catalogue est gardée (un démarrage relance aussi).
                        val partial = Catalog(
                            catalog.channels, catalog.movies, catalog.series, catalog.channelCategories,
                            catalog.movieCategories, catalog.seriesCategories, cached?.fetchedAt ?: 0L,
                        )
                        publish(gen, State.Ready(partial, missing = missing.toList()))
                        writeCache(ctx, gen, partial, account, missing)
                        scheduleRetry(app, gen)
                    } else {
                        publish(gen, State.Ready(catalog)) // affiché tout de suite…
                        writeCache(ctx, gen, catalog, account, emptyList()) // …puis mémorisé pour le prochain démarrage
                    }
                    startEnrichment(ctx, settings, gen)
                } catch (e: Exception) {
                    publish(gen, if (cached != null) State.Ready(cached, missing = cachedMissing) else State.Failed(friendly(e)))
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (cached != null) startEnrichment(ctx, settings, gen)
                }
            }
        }
    }

    /** Catalogue incomplet : nouvel essai (sans forcer) dans 15 min, tant que le compte n'a pas changé. */
    private fun scheduleRetry(app: Context, gen: Int) {
        retryJob?.cancel()
        retryJob = bgScope.launch {
            kotlinx.coroutines.delay(PARTIAL_RETRY_MS)
            retryJob = null // sinon load() annulerait ce même essai
            if (isCurrent(gen)) runCatching { load(app) }
        }
    }

    /**
     * Déconnexion : oublie le catalogue. Un chargement en cours pour l'ancien compte ne republiera rien et
     * n'écrira plus le cache (génération incrémentée sous le même verrou que les publications).
     */
    fun clear(ctx: Context) {
        synchronized(genLock) {
            generation++
            readyGeneration = -1
            currentAccount = null
            _state.value = State.Idle
        }
        retryJob?.cancel()
        episodeCache.clear()
        cacheFile(ctx).delete()
    }

    // ---------------------------------------------------------------- Épisodes à la demande (hors interface)

    private class EpisodesEntry(val at: Long, val episodes: List<Episode>?)
    private val episodeCache = java.util.concurrent.ConcurrentHashMap<String, EpisodesEntry>()
    private const val EPISODES_TTL_MS = 6 * 60 * 60 * 1000L
    private const val EPISODES_FAIL_TTL_MS = 2 * 60 * 1000L

    /**
     * Épisodes d'une série (triés saison / numéro, adresses de lecture comprises) : ceux du catalogue (M3U),
     * sinon get_series_info du serveur Xtream (petit cache : 6 h, 2 min après un échec). Null si inconnus.
     * Bloquant (réseau) : jamais sur le fil principal. Sert à Watch Next ([com.fluxfr.tv.tvhome.WatchNextSync]).
     */
    fun seriesEpisodes(ctx: Context, seriesId: String, catalog: Catalog? = null): List<Episode>? {
        (catalog ?: (_state.value as? State.Ready)?.catalog)?.unfiltered?.seriesById?.get(seriesId)?.episodes
            ?.takeIf { it.isNotEmpty() }
            ?.let { eps -> return eps.sortedWith(compareBy<Episode>({ it.season }, { it.number })) }
        if (seriesId.startsWith("m3u:")) return null
        val now = System.currentTimeMillis()
        episodeCache[seriesId]?.let { e ->
            val ttl = if (e.episodes != null) EPISODES_TTL_MS else EPISODES_FAIL_TTL_MS
            if (now - e.at < ttl) return e.episodes
        }
        val s = Settings(ctx.applicationContext)
        if (s.mode != Settings.MODE_XTREAM || s.server.isBlank()) return null
        val gen = generation
        val eps = runCatching { XtreamClient(s.server, s.user, s.pass).seriesInfo(seriesId).episodes }.getOrNull()
            ?.sortedWith(compareBy<Episode>({ it.season }, { it.number }))
        if (isCurrent(gen)) episodeCache[seriesId] = EpisodesEntry(now, eps?.takeIf { it.isNotEmpty() })
        return eps?.takeIf { it.isNotEmpty() }
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException -> "Serveur introuvable. Vérifie l'adresse du serveur et la connexion Internet."
        is SocketTimeoutException -> "Le serveur met trop de temps à répondre. Réessaie dans un instant."
        is java.net.ConnectException -> "Impossible de joindre le serveur. Vérifie la connexion Internet."
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * Progression : chaînes, films et séries sont téléchargés EN PARALLÈLE ; chaque flux a sa part
     * de la barre, qui avance selon le nombre d'éléments lus (le total n'est pas connu d'avance).
     */
    private class Tracker(val cached: Catalog?, val gen: Int) {
        private val scanned = IntArray(3)
        private val kept = IntArray(3)
        private val done = BooleanArray(3)
        private val weights = floatArrayOf(0.12f, 0.58f, 0.30f)
        private val k = floatArrayOf(1500f, 9000f, 3000f)
        private val catFrac = FloatArray(3) { -1f }

        /** Part des catégories déjà téléchargées pour une étape (0..1). */
        @Synchronized
        fun progress(stage: Int, f: Float) {
            catFrac[stage] = f.coerceIn(0f, 1f)
            emitNow()
        }
        private var lastEmit = 0L

        @Synchronized
        fun seen(stage: Int, keptIt: Boolean) {
            scanned[stage]++
            if (keptIt) kept[stage]++
            val now = System.nanoTime()
            if (now - lastEmit > 200_000_000L) emitNow()
        }

        @Synchronized
        fun finish(stage: Int) {
            done[stage] = true
            emitNow()
        }

        private fun emitNow() {
            lastEmit = System.nanoTime()
            var frac = 0.04f
            for (i in 0..2) {
                val f = when {
                    done[i] -> 1f
                    catFrac[i] >= 0f -> catFrac[i]
                    else -> 1f - exp(-scanned[i] / k[i])
                }
                frac += 0.92f * weights[i] * f
            }
            val detail = "%,d chaînes · %,d films · %,d séries".format(kept[0], kept[1], kept[2])
                .replace(',', ' ')
            emit("Chargement du catalogue…", frac, detail)
        }

        fun emit(msg: String, frac: Float, detail: String?) {
            publish(
                gen,
                if (cached != null) State.Ready(cached, refreshing = true, progress = frac)
                else State.Loading(msg, frac, detail),
            )
        }
    }

    // ---------------------------------------------------------------- Xtream

    /**
     * [previous] : catalogue précédent (repris pour les catégories que le serveur refuse) ; [missing] reçoit
     * les catégories toujours en échec après les nouveaux essais.
     */
    private suspend fun fromXtream(
        ctx: Context, s: Settings, f: ContentFilter, t: Tracker,
        previous: Catalog?, missing: MutableList<String>,
    ): Catalog = coroutineScope {
        val x = XtreamClient(s.server, s.user, s.pass)
        x.authenticate()?.let { throw IOException(it) }
        // Le serveur a pu basculer en http (certificat invalide) : on retient l'adresse qui marche.
        if (x.base != XtreamClient.normalize(s.server)) s.server = x.base
        val report = if (BuildConfig.DEBUG) FilterReport() else null
        t.emit("Chargement du catalogue…", 0.04f, null)
        val multiCandidates = ArrayList<Movie>()
        // Séries des catégories MULTI : les langues ne sont pas vérifiables (le fournisseur ne donne au mieux que
        // la 1re piste audio d'un épisode) ; montrées à part si l'option l'autorise.
        val multiSeries = ArrayList<Series>()
        VodMeta.load(ctx) // avant tout put : sinon le chargement écraserait les nouvelles valeurs

        // Seules les catégories utiles (françaises, MULTI ou sans pays) sont téléchargées, en parallèle :
        // chez les gros fournisseurs, c'est ~10 fois moins de données que la liste complète.
        // 4 requêtes simultanées au plus, 1 seule dès le premier refus (403 / 429 : abonnement à 1-2 connexions).
        val gate = AdaptiveGate(CatalogFetchRules.INITIAL_PARALLELISM)

        /**
         * Parcourt les entrées des catégories retenues ; repli sur la liste complète si le panel ignore category_id.
         * Une catégorie en échec est réessayée une à une ; si elle échoue encore, [fallback] reprend son contenu
         * précédent et elle est notée dans [missing] (« [label] › catégorie »).
         */
        suspend fun fetch(
            stage: Int, action: String, cats: List<Pair<String, String>>, label: String,
            fallback: (String) -> Unit,
            handle: (Map<String, String?>, String) -> Unit,
        ) {
            val wanted = cats.filter { f.worthFetching(it.second) }
            val catName = cats.toMap()
            var perCategory = wanted.isNotEmpty()
            if (perCategory) {
                // Sonde : le serveur respecte-t-il le filtre par catégorie ?
                val (probeId, probeName) = wanted.first()
                var mismatch = false
                x.forEachItem(action, "&category_id=" + probeId) { m ->
                    if (m["category_id"] != null && m["category_id"] != probeId) mismatch = true
                    handle(m, catName[m["category_id"]] ?: probeName)
                }
                if (mismatch) perCategory = false
            }
            if (perCategory) {
                var done = 1
                t.progress(stage, done.toFloat() / wanted.size)
                val failed = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())
                /** Une catégorie : entrées gardées en mémoire et transmises seulement si la lecture a abouti. */
                fun one(id: String, name: String) {
                    val buf = ArrayList<Map<String, String?>>()
                    x.forEachItem(action, "&category_id=" + id) { m -> buf += m }
                    buf.forEach { m -> handle(m, name) }
                }
                coroutineScope {
                    wanted.drop(1).map { (id, name) ->
                        async(Dispatchers.IO) {
                            gate.withPermit {
                                try {
                                    one(id, name)
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    if (CatalogFetchRules.isThrottle(e)) gate.reduceTo(1)
                                    failed += id to name
                                }
                                synchronized(t) { done++; t.progress(stage, done.toFloat() / wanted.size) }
                            }
                        }
                    }.awaitAll()
                }
                // Nouveaux essais, une catégorie à la fois, avec une petite pause ; on abandonne après
                // plusieurs échecs de suite (le serveur refuse clairement).
                var streak = 0
                for ((id, name) in failed.toList()) {
                    var ok = false
                    if (streak < CatalogFetchRules.MAX_FAILED_STREAK) {
                        for (attempt in 1..CatalogFetchRules.RETRIES) {
                            kotlinx.coroutines.delay(CatalogFetchRules.retryDelayMs(attempt))
                            ok = gate.withPermit { runCatching { one(id, name) }.isSuccess }
                            if (ok) break
                        }
                    }
                    if (ok) { streak = 0; continue }
                    streak++
                    synchronized(missing) { missing += "$label › ${cleanCategory(name)}" }
                    fallback(name)
                }
            } else {
                x.forEachItem(action) { m -> handle(m, catName[m["category_id"]] ?: "") }
            }
            t.finish(stage)
        }

        val live = async(Dispatchers.IO) {
            val cats = x.categories("get_live_categories")
            val out = ArrayList<RawLive>()
            val keep: (String) -> Unit = { name ->
                val cat = cleanCategory(name)
                val old = previous?.channels?.filter { it.category == cat }.orEmpty()
                synchronized(out) {
                    // Une variante par source : l'assemblage reconstitue la chaîne et ses secours.
                    for (ch in old) for (src in ch.sources) {
                        out += RawLive(ch.id, ch.name, ch.category, ch.logo, src.quality, src.url, null, ch.num, ch.archiveDays)
                    }
                }
            }
            fetch(0, "get_live_streams", cats, "TV", keep) { m, cat ->
                val name = m["name"]?.takeIf { it.isNotBlank() }
                val id = m["stream_id"]
                val ok = name != null && id != null && f.isFrench(cat, name) && !f.isExcluded(cat, name) &&
                    !f.isPlaceholder(name)
                report?.add("TV", cat, ok)
                t.seen(0, ok)
                if (ok) synchronized(out) {
                    out += RawLive(
                        id = id!!, name = cleanName(name!!), cat = cleanCategory(cat), logo = m["stream_icon"],
                        q = f.itemQuality(name, cat), url = x.liveUrl(id, "ts"), hlsUrl = x.liveUrl(id, "m3u8"),
                        num = m["num"]?.toIntOrNull() ?: 0,
                        archive = archiveDays(m["tv_archive"], m["tv_archive_duration"]),
                    )
                }
            }
            cats.map { cleanCategory(it.second) } to out
        }
        val vod = async(Dispatchers.IO) {
            val cats = x.categories("get_vod_categories")
            val out = ArrayList<Movie>()
            val keep: (String) -> Unit = { name ->
                val cat = cleanCategory(name)
                val old = previous?.movies?.filter { it.category == cat }.orEmpty()
                synchronized(out) { out += old }
            }
            fetch(1, "get_vod_streams", cats, "Films", keep) { m, cat ->
                val name = m["name"]?.takeIf { it.isNotBlank() }
                val id = m["stream_id"]
                val french = name != null && id != null && f.isFrench(cat, name) && !f.isExcluded(cat, name)
                report?.add("Films", cat, french)
                // Films "MULTI-LANG" : on ne sait pas d'après le nom ; candidats à la vérification audio.
                val multi = !french && name != null && id != null &&
                    ContentFilter.tokens(cat).contains("MULTI") && !f.isExcluded(cat, name)
                val q = if (french || multi) f.vodQuality(name!!, cat) else Quality.UNKNOWN
                val ok = french && f.acceptVod(q)
                t.seen(1, ok)
                if (!ok && !(multi && f.acceptVod(q))) return@fetch
                val ext = m["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4"
                val movie = Movie(
                    id!!, cleanName(name!!), if (ok) cleanCategory(cat) else MultiAudio.CATEGORY,
                    m["stream_icon"]?.takeIf { it.isNotBlank() }, q,
                    x.movieUrl(id, ext), m["added"]?.toLongOrNull() ?: 0L, m["rating"],
                )
                movieListMeta(id!!, m)
                if (ok) synchronized(out) { out += movie } else synchronized(multiCandidates) { multiCandidates += movie }
            }
            cats.map { cleanCategory(it.second) } to out
        }
        val ser = async(Dispatchers.IO) {
            val cats = x.categories("get_series_categories")
            val out = ArrayList<Series>()
            val keep: (String) -> Unit = { name ->
                val cat = cleanCategory(name)
                val old = previous?.series?.filter { it.category == cat }.orEmpty()
                synchronized(out) { out += old }
            }
            fetch(2, "get_series", cats, "Séries", keep) { m, cat ->
                val name = m["name"]?.takeIf { it.isNotBlank() }
                val id = m["series_id"]
                val french = name != null && id != null && f.isFrench(cat, name) && !f.isExcluded(cat, name)
                report?.add("Séries", cat, french)
                val multi = !french && name != null && id != null &&
                    ContentFilter.tokens(cat).contains("MULTI") && !f.isExcluded(cat, name)
                val q = if (french || multi) f.vodQuality(name!!, cat) else Quality.UNKNOWN
                val ok = french && f.acceptVod(q)
                t.seen(2, ok)
                if (multi && f.acceptVod(q)) {
                    seriesMeta(id!!, m)
                    synchronized(multiSeries) {
                        multiSeries += Series(
                            id, cleanName(name!!), MultiAudio.UNVERIFIED_CATEGORY, m["cover"]?.takeIf { it.isNotBlank() }, q,
                            m["plot"], m["last_modified"]?.toLongOrNull() ?: 0L,
                        )
                    }
                }
                if (ok) seriesMeta(id!!, m)
                if (ok) synchronized(out) {
                    out += Series(
                        id!!, cleanName(name!!), cleanCategory(cat), m["cover"]?.takeIf { it.isNotBlank() }, q,
                        m["plot"], m["last_modified"]?.toLongOrNull() ?: 0L,
                    )
                }
            }
            cats.map { cleanCategory(it.second) } to out
        }

        val (liveOrder, rawLive) = live.await()
        val (movieOrder, movies) = vod.await()
        val (seriesOrder, series) = ser.await()
        report?.log()
        t.emit("Finalisation…", 0.97f, null)
        VodMeta.save(ctx)
        // Films MULTI dont la VF a déjà été vérifiée lors d'un passage précédent.
        val audio = MultiAudio.load(ctx)
        val verified = multiCandidates.filter { audio[it.id] == MultiAudio.FRENCH }
        pendingAudioCheck = multiCandidates.filter { audio[it.id] == null }
        // VF impossible à vérifier (langues non fournies, ou pas encore vérifiées) : catégorie à part, si l'option
        // est activée ; jamais un titre déjà présent en version française. Une VO avérée (OTHER) reste masquée.
        val unverifiedMovies = if (!s.showUnverifiedMulti) emptyList() else {
            val known = (movies + verified).mapTo(HashSet()) { dedupKey(it.name) }
            multiCandidates.filter { audio[it.id] != MultiAudio.FRENCH && audio[it.id] != MultiAudio.OTHER }
                .filter { known.add(dedupKey(it.name)) }
                .map { it.copy(category = MultiAudio.UNVERIFIED_CATEGORY) }
        }
        val unverifiedSeries = if (!s.showUnverifiedMulti) emptyList() else {
            val known = series.mapTo(HashSet()) { dedupKey(it.name) }
            multiSeries.filter { known.add(dedupKey(it.name)) }
        }
        assemble(
            f, rawLive, movies + verified + unverifiedMovies, series + unverifiedSeries, liveOrder,
            movieOrder + listOfNotNull(
                MultiAudio.CATEGORY.takeIf { verified.isNotEmpty() },
                MultiAudio.UNVERIFIED_CATEGORY.takeIf { unverifiedMovies.isNotEmpty() },
            ),
            seriesOrder + listOfNotNull(MultiAudio.UNVERIFIED_CATEGORY.takeIf { unverifiedSeries.isNotEmpty() }),
        )
    }

    // ---------------------------------------------------------------- Vérification audio des films MULTI

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pendingAudioCheck: List<Movie> = emptyList()

    /**
     * En arrière-plan ([MetaEnricher]), un seul passage get_vod_info par film :
     * - films MULTI-LANG pas encore vérifiés : ceux qui ont une piste française apparaissent (en une
     *   fois, à la fin) dans la catégorie "Multi-langues (VF vérifiée)" ;
     * - films sans fiche [VodMeta] : genre, identifiants TMDB/IMDB, année.
     */
    private fun startEnrichment(ctx: Context, s: Settings, gen: Int) {
        val multi = pendingAudioCheck
        pendingAudioCheck = emptyList()
        if (!isCurrent(gen)) return
        val movies = (_state.value as? State.Ready)?.catalog?.unfiltered?.movies ?: return
        val account = accountKey(s)
        MetaEnricher.start(bgScope, ctx, s, movies, multi) { found ->
            if (!isCurrent(gen)) return@start // déconnexion / autre compte entre-temps
            publishMovies(ctx, found, gen)
            (_state.value as? State.Ready)?.let { st -> writeCache(ctx, gen, st.catalog, account, st.missing) }
        }
    }

    /** Genres / identifiants des séries, lus directement dans la liste get_series (gratuit). */
    private fun seriesMeta(id: String, m: Map<String, String?>) {
        val key = VodMeta.seriesKey(id)
        val meta = Meta(
            genres = Genres.normalize(m["genre"] ?: m["genres"]),
            tmdbId = MetaEnricher.tmdbOf(m["tmdb"] ?: m["tmdb_id"]),
            imdbId = null,
            year = MetaEnricher.yearOf(m["year"], m["releaseDate"], m["release_date"], m["releasedate"]),
        )
        VodMeta.put(key, MetaEnricher.merge(VodMeta.get(key), meta))
    }

    /** Certains panels donnent aussi le genre dans get_vod_streams : autant le prendre. */
    private fun movieListMeta(id: String, m: Map<String, String?>) {
        val genres = Genres.normalize(m["genre"] ?: m["genres"])
        if (genres.isEmpty()) return
        val key = VodMeta.movieKey(id)
        if (VodMeta.get(key)?.genres?.isNotEmpty() == true) return
        val meta = Meta(
            genres = genres,
            tmdbId = MetaEnricher.tmdbOf(m["tmdb"] ?: m["tmdb_id"]),
            imdbId = null,
            year = MetaEnricher.yearOf(m["year"], m["releaseDate"], m["release_date"]),
        )
        VodMeta.put(key, MetaEnricher.merge(VodMeta.get(key), meta))
    }

    /** Ajoute des films au catalogue affiché, sans doublon de titre. */
    @Synchronized
    private fun publishMovies(ctx: Context, extra: List<Movie>, gen: Int) {
        if (!isCurrent(gen)) return
        val st = _state.value as? State.Ready ?: return
        val c = st.catalog
        val keys = c.movies.mapTo(HashSet()) { dedupKey(it.name) }
        val add = extra.filter { keys.add(dedupKey(it.name)) }
        if (add.isEmpty()) return
        val movies = (c.movies + add).sortedByDescending { it.added }
        val cats = if (MultiAudio.CATEGORY in c.movieCategories) c.movieCategories else c.movieCategories + MultiAudio.CATEGORY
        publish(
            gen,
            st.copy(catalog = Catalog(c.channels, movies, c.series, c.channelCategories, cats, c.seriesCategories, c.fetchedAt)),
        )
    }

    /** Version de test uniquement : liste dans logcat les catégories gardées / rejetées par le filtre. */
    private class FilterReport {
        private val stats = LinkedHashMap<String, IntArray>()

        @Synchronized
        fun add(kind: String, cat: String, kept: Boolean) {
            val a = stats.getOrPut("$kind | $cat") { IntArray(2) }
            a[0]++; if (kept) a[1]++
        }

        @Synchronized
        fun log() {
            stats.forEach { (k, a) ->
                Log.i("FluxFilter", (if (a[1] > 0) "GARDE " else "rejet ") + "${a[1]}/${a[0]}  $k")
            }
        }
    }

    // ---------------------------------------------------------------- M3U

    private val EPISODE = Regex("^(.*?)[\\s._-]*[Ss](\\d{1,2})[\\s._-]*[Ee](\\d{1,4})(?!\\d)(.*)$")
    private val VIDEO_EXT = Regex("\\.(mp4|mkv|avi|mov|m4v)(\\?.*)?$", RegexOption.IGNORE_CASE)

    /** Identifiant stable sur 64 bits (FNV-1a) : évite les collisions des hashCode 32 bits. */
    private fun stableId(s: String): String {
        var h = -0x340d631b7bdddcdbL
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h)
    }

    private fun fromM3u(s: Settings, f: ContentFilter, t: Tracker): Catalog {
        t.emit("Téléchargement de la playlist…", 0.05f, null)
        val rawLive = ArrayList<RawLive>()
        val movies = ArrayList<Movie>()
        val seriesEps = LinkedHashMap<String, MutableList<Pair<M3uEntry, Episode>>>()
        val seriesMeta = HashMap<String, Triple<String, String, Quality>>() // nom, catégorie, qualité
        val liveOrder = LinkedHashSet<String>()
        val movieOrder = LinkedHashSet<String>()
        val seriesOrder = LinkedHashSet<String>()
        var index = 0L

        M3uParser.parse(s.m3uUrl) { e ->
            index++
            val french = f.isFrench(e.group, e.name) && !f.isExcluded(e.group, e.name)
            val cat = cleanCategory(e.group)
            val id = stableId(e.url)
            val isSeries = e.url.contains("/series/", true)
            val isMovie = e.url.contains("/movie/", true) || (!isSeries && VIDEO_EXT.containsMatchIn(e.url))
            val q = when {
                !french -> Quality.UNKNOWN
                isSeries || isMovie -> f.vodQuality(e.name, e.group)
                else -> f.itemQuality(e.name, e.group)
            }
            val stage = if (isSeries) 2 else if (isMovie) 1 else 0
            if (!french || ((isSeries || isMovie) && !f.acceptVod(q))) {
                t.seen(stage, false)
                return@parse
            }
            t.seen(stage, true)
            val ep = if (isSeries || isMovie) EPISODE.find(e.name) else null
            when {
                ep != null -> {
                    val sName = cleanName(ep.groupValues[1])
                    val key = dedupKey(sName)
                    val season = ep.groupValues[2].toInt()
                    val number = ep.groupValues[3].toInt()
                    val title = ep.groupValues[4].trim { it.isWhitespace() || it in "-:|" }
                    seriesOrder += cat
                    seriesMeta.getOrPut(key) { Triple(sName, cat, q) }
                    seriesEps.getOrPut(key) { ArrayList() } +=
                        e to Episode(id, season, number, title, e.url, e.logo, null, 0L)
                }
                isMovie -> {
                    movieOrder += cat
                    movies += Movie(id, cleanName(e.name), cat, e.logo, q, e.url, -index, null)
                }
                else -> {
                    liveOrder += cat
                    rawLive += RawLive(id, cleanName(e.name), cat, e.logo, q, e.url, null, 0)
                }
            }
        }

        t.emit("Finalisation…", 0.97f, null)
        var order = 0L
        val series = seriesEps.map { (key, list) ->
            val (name, cat, q) = seriesMeta.getValue(key)
            val eps = list.map { it.second }.distinctBy { it.season to it.number }
                .sortedWith(compareBy({ it.season }, { it.number }))
            Series("m3u:$key", name, cat, list.firstNotNullOfOrNull { it.first.logo }, q, null, -(order++), eps)
        }
        return assemble(f, rawLive, movies, series, liveOrder.toList(), movieOrder.toList(), seriesOrder.toList())
    }

    // ---------------------------------------------------------------- Assemblage

    private class RawLive(
        val id: String, val name: String, val cat: String, val logo: String?,
        val q: Quality, val url: String, val hlsUrl: String?, val num: Int,
        /** Jours de replay (tv_archive), 0 = aucun. */
        val archive: Int = 0,
    )

    /** tv_archive = 1 → tv_archive_duration jours (1 au moins) ; sinon 0. */
    private fun archiveDays(flag: String?, duration: String?): Int {
        val on = flag?.trim().let { it == "1" || it.equals("true", ignoreCase = true) }
        if (!on) return 0
        return (duration?.trim()?.toDoubleOrNull()?.toInt() ?: 1).coerceIn(1, 60)
    }

    private fun assemble(
        f: ContentFilter,
        rawLive: List<RawLive>,
        rawMovies: List<Movie>,
        rawSeries: List<Series>,
        liveOrder: List<String>,
        movieOrder: List<String>,
        seriesOrder: List<String>,
    ): Catalog {
        // Chaînes : une seule entrée par chaîne (sa version préférée), les autres deviennent des secours.
        // Par défaut FHD > HD > 4K : chez beaucoup de fournisseurs, la "4K" n'est pas le vrai flux.
        val now = System.currentTimeMillis()
        val channels = rawLive.groupBy { dedupKey(it.name) }.values.mapNotNull { variants ->
            val sorted = variants.sortedByDescending { f.liveRank(it.q) }
            val best = sorted.first()
            if (!f.acceptLive(best.q, best.name)) return@mapNotNull null
            // Chaîne d'un événement terminé depuis longtemps (PPV d'il y a des semaines) : inutile.
            if (EventChannels.isStale(best.name, now)) return@mapNotNull null
            val sources = sorted.map { Source(it.url, it.q) } + listOfNotNull(best.hlsUrl?.let { Source(it, best.q) })
            Channel(
                best.id, best.name, best.cat,
                sorted.firstNotNullOfOrNull { v -> v.logo?.takeIf { it.isNotBlank() } },
                best.q, sources.distinctBy { it.url }, best.num, archiveDays = best.archive,
            )
        }.distinctBy { it.id }
        val liveIdx = liveOrder.distinct().withIndex().associate { it.value to it.index }
        val sortedChannels = channels.sortedWith(
            compareBy<Channel>(
                { liveIdx[it.category] ?: Int.MAX_VALUE },
                { if (it.num > 0) it.num else Int.MAX_VALUE },
                { it.name.lowercase() },
            )
        )

        // Films et séries : on ne garde que la meilleure version d'un même titre.
        val movies = rawMovies.groupBy { dedupKey(it.name) }.values
            .map { v -> v.maxBy { it.quality.rank } }
            .distinctBy { it.id }
            .sortedByDescending { it.added }
        val series = rawSeries.groupBy { dedupKey(it.name) }.values
            .map { v -> v.maxBy { it.quality.rank } }
            .distinctBy { it.id }
            .sortedByDescending { it.added }

        fun order(all: List<String>, used: Set<String>) = all.distinct().filter { it in used }

        return Catalog(
            channels = sortedChannels,
            movies = movies,
            series = series,
            channelCategories = order(liveOrder, sortedChannels.mapTo(HashSet()) { it.category }),
            movieCategories = order(movieOrder, movies.mapTo(HashSet()) { it.category }),
            seriesCategories = order(seriesOrder, series.mapTo(HashSet()) { it.category }),
            fetchedAt = System.currentTimeMillis(),
        )
    }
}

/** Règles pures (testées) du téléchargement du catalogue. */
internal object CatalogFetchRules {
    /** Requêtes de catégories simultanées au départ ; 1 seule après le premier 403 / 429. */
    const val INITIAL_PARALLELISM = 4
    /** Nouveaux essais par catégorie en échec (un à la fois). */
    const val RETRIES = 2
    /** Après ce nombre de catégories en échec d'affilée (malgré les essais), on n'insiste plus. */
    const val MAX_FAILED_STREAK = 3

    /** Pause avant le n-ième nouvel essai (1 s, puis 3 s). */
    fun retryDelayMs(attempt: Int): Long = if (attempt <= 1) 1_000L else 3_000L

    /** Refus du serveur pour cause de connexions simultanées / limite de débit (HTTP 403 ou 429). */
    fun isThrottle(e: Throwable?): Boolean {
        var t = e
        var depth = 0
        while (t != null && depth++ < 5) {
            val m = t.message.orEmpty()
            if (Regex("""HTTP 4(03|29)\b""").containsMatchIn(m)) return true
            t = t.cause
        }
        return false
    }

    /** Rafraîchir ? Oui si forcé, si le catalogue a plus de [maxAgeMs], ou s'il est incomplet. */
    fun needsRefresh(fetchedAt: Long, now: Long, maxAgeMs: Long, incomplete: Boolean, force: Boolean): Boolean =
        force || incomplete || now - fetchedAt >= maxAgeMs

    /**
     * Empreinte du compte IPTV (FNV-1a 64 bits ; jamais le mot de passe) : adresse sans protocole (le passage
     * https → http ne change pas de compte) + utilisateur, ou adresse de la liste M3U.
     */
    fun accountKey(m3u: Boolean, m3uUrl: String, server: String, user: String): String {
        val raw = if (m3u) "m3u|" + m3uUrl.trim()
        else "x|" + server.trim().trimEnd('/').substringAfter("://").lowercase() + "|" + user.trim()
        var h = -0x340d631b7bdddcdbL
        for (c in raw) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h)
    }
}

/**
 * Limiteur de requêtes simultanées dont la limite peut baisser en cours de route ([reduceTo]) : les requêtes
 * déjà lancées se terminent, les suivantes attendent qu'on repasse sous la nouvelle limite.
 */
internal class AdaptiveGate(initial: Int) {
    private val lock = Any()
    private var limit = initial.coerceAtLeast(1)
    private var active = 0
    private val waiters = ArrayDeque<kotlinx.coroutines.CompletableDeferred<Unit>>()

    val currentLimit: Int get() = synchronized(lock) { limit }
    val activeCount: Int get() = synchronized(lock) { active }

    fun reduceTo(n: Int) = synchronized(lock) { if (n in 1 until limit) limit = n }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            synchronized(lock) { releaseLocked() }
        }
    }

    private suspend fun acquire() {
        val d = kotlinx.coroutines.CompletableDeferred<Unit>()
        synchronized(lock) {
            if (active < limit) { active++; return }
            waiters.addLast(d)
        }
        try {
            d.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Place accordée entre-temps : on la rend.
            synchronized(lock) { if (!waiters.remove(d)) releaseLocked() }
            throw e
        }
    }

    private fun releaseLocked() {
        active--
        while (active < limit && waiters.isNotEmpty()) {
            active++
            waiters.removeFirst().complete(Unit)
        }
    }
}
