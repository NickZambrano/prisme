package com.fluxfr.tv.data

import android.content.Context
import android.util.Log
import com.fluxfr.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * Enrichissement des films en arrière-plan : un seul passage get_vod_info par film collecte
 * à la fois les langues audio (films MULTI-LANG à vérifier, voir [MultiAudio]) et le genre,
 * les identifiants TMDB / IMDB et l'année (mémorisés dans [VodMeta]).
 */
object MetaEnricher {
    private const val TAG = "FluxMeta"
    private const val CONCURRENCY = 4
    private const val SAVE_EVERY = 200
    private const val NO_GENRE_FILE = "__nogenre"
    private const val NO_GENRE_RETRY_MS = 7L * 24 * 60 * 60 * 1000

    private val _version = MutableStateFlow(0)
    /** Incrémenté après chaque lot enregistré : l'interface recalcule alors ses listes de genres. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _running = MutableStateFlow(false)
    /** Vrai tant que l'analyse des films est en cours. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null

    /** Le fournisseur ne renseigne ni genre ni identifiant TMDB : on ne réessaie qu'au bout de 7 jours. */
    fun providerHasNoGenres(ctx: Context): Boolean {
        val f = File(ctx.filesDir, NO_GENRE_FILE)
        return f.exists() && System.currentTimeMillis() - f.lastModified() < NO_GENRE_RETRY_MS
    }

    private fun markProviderHasNoGenres(ctx: Context) {
        runCatching { File(ctx.filesDir, NO_GENRE_FILE).writeText(System.currentTimeMillis().toString()) }
    }

    /** Année plausible (1900-2099) trouvée dans une des valeurs. */
    fun yearOf(vararg raw: String?): Int? = raw.firstNotNullOfOrNull { s ->
        s?.let { YEAR.find(it)?.value?.toIntOrNull() }
    }

    private val YEAR = Regex("(19|20)\\d{2}")
    private val DIGITS = Regex("\\d{1,9}")

    /** Identifiant TMDB valide (numérique, non nul) ou null. */
    fun tmdbOf(raw: String?): String? = raw?.trim()?.takeIf { DIGITS.matches(it) && it.trimStart('0').isNotEmpty() }

    /** Fusionne de nouvelles infos avec celles déjà connues (sans perdre un identifiant). */
    fun merge(old: Meta?, new: Meta): Meta = if (old == null) new else Meta(
        genres = new.genres.ifEmpty { old.genres },
        tmdbId = new.tmdbId ?: old.tmdbId,
        imdbId = new.imdbId ?: old.imdbId,
        year = new.year ?: old.year,
    )

    private class Work(val movie: Movie, val audio: Boolean, val meta: Boolean)

    /**
     * Lance (ou relance) l'analyse : d'abord les films MULTI à vérifier ([multi]), puis tous les films
     * de [movies] sans fiche [VodMeta]. [onVerified] reçoit, à la fin, les films MULTI avec VF trouvée.
     * Ne fait rien si un passage est déjà en cours et qu'il n'y a pas de nouveau film MULTI.
     */
    fun start(
        scope: CoroutineScope,
        ctx: Context,
        s: Settings,
        movies: List<Movie>,
        multi: List<Movie>,
        onVerified: (List<Movie>) -> Unit,
    ) {
        if (s.mode != Settings.MODE_XTREAM) return
        if (job?.isActive == true && multi.isEmpty()) return
        val app = ctx.applicationContext
        job?.cancel()
        job = scope.launch {
            VodMeta.load(app)
            val checkAudio = multi.isNotEmpty() && !MultiAudio.providerHasNoInfo(app)
            val checkMeta = !providerHasNoGenres(app)
            val seen = HashSet<String>()
            val work = ArrayList<Work>()
            if (checkAudio) for (m in multi) if (seen.add(m.id)) {
                work += Work(m, audio = true, meta = !VodMeta.has(VodMeta.movieKey(m.id)))
            }
            if (checkMeta) for (m in movies) if (seen.add(m.id) && !VodMeta.has(VodMeta.movieKey(m.id))) {
                work += Work(m, audio = false, meta = true)
            }
            if (work.isEmpty()) return@launch
            _running.value = true
            try {
                delay(4_000) // laisse l'interface et les affiches se charger d'abord
                run(app, s, work, onVerified)
            } finally {
                VodMeta.save(app) // aussi en cas d'annulation : rien de ce qui a été lu n'est perdu
                _running.value = false
                _version.value++
            }
        }
    }

    private suspend fun run(ctx: Context, s: Settings, work: List<Work>, onVerified: (List<Movie>) -> Unit) =
        kotlinx.coroutines.coroutineScope {
            val x = XtreamClient(s.server, s.user, s.pass)
            val audioKnown = MultiAudio.load(ctx)
            val sem = Semaphore(CONCURRENCY)
            var audioStopped = false
            var metaStopped = false
            var audioChecked = 0
            var audioWithInfo = 0
            var metaFetched = 0
            var metaUseful = 0
            var sinceSave = 0
            var done = 0
            var failedChunks = 0
            val found = ArrayList<Movie>()

            fun save() {
                VodMeta.save(ctx)
                MultiAudio.save(ctx, audioKnown)
                sinceSave = 0
                _version.value++
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG, "analysés $done/${work.size} · genres/ids $metaUseful/$metaFetched · " +
                            "audio $audioWithInfo/$audioChecked · VF trouvées ${found.size}",
                    )
                }
            }

            for (chunk in work.chunked(CONCURRENCY * 12)) {
                val todo = chunk.filter { (it.audio && !audioStopped) || (it.meta && !metaStopped) }
                if (todo.isEmpty()) continue
                val results = todo.map { w ->
                    async { sem.withPermit { w to runCatching { x.vodDetails(w.movie.id) }.getOrNull() } }
                }.awaitAll()
                var ok = 0
                for ((w, d) in results) {
                    done++
                    if (d == null) continue // erreur réseau : sera retenté au prochain passage
                    ok++
                    if (w.audio && !audioStopped) {
                        val v = MultiAudio.classify(d.audioLanguages)
                        audioKnown[w.movie.id] = v
                        audioChecked++
                        if (v != MultiAudio.UNKNOWN) audioWithInfo++
                        if (v == MultiAudio.FRENCH) found += w.movie
                    }
                    val key = VodMeta.movieKey(w.movie.id)
                    val meta = Meta(Genres.normalize(d.genre), d.tmdbId, d.imdbId, d.year)
                    if (w.meta) {
                        metaFetched++
                        if (meta.genres.isNotEmpty() || meta.tmdbId != null) metaUseful++
                    }
                    VodMeta.put(key, merge(VodMeta.get(key), meta))
                    sinceSave++
                }
                failedChunks = if (ok == 0) failedChunks + 1 else 0
                if (failedChunks >= 3) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "serveur injoignable : arrêt")
                    break
                }
                // Le fournisseur ne renseigne pas les langues / les genres : inutile d'insister.
                if (!audioStopped && audioChecked >= 120 && audioWithInfo == 0) {
                    audioStopped = true
                    MultiAudio.markProviderHasNoInfo(ctx)
                    if (BuildConfig.DEBUG) Log.i(TAG, "aucune info de langue chez ce fournisseur")
                }
                if (!metaStopped && metaFetched >= 300 && metaUseful == 0) {
                    metaStopped = true
                    markProviderHasNoGenres(ctx)
                    if (BuildConfig.DEBUG) Log.i(TAG, "aucun genre chez ce fournisseur")
                }
                if (sinceSave >= SAVE_EVERY) save()
            }
            save()
            if (found.isNotEmpty()) onVerified(found)
        }
}
