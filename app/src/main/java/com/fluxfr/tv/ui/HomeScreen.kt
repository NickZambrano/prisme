package com.fluxfr.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.fluxfr.tv.data.AllocineTrailer
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.Channel
import com.fluxfr.tv.data.DisplayTitle
import com.fluxfr.tv.data.Epg
import com.fluxfr.tv.data.Favorites
import com.fluxfr.tv.data.Match
import com.fluxfr.tv.data.Movie
import com.fluxfr.tv.data.Progress
import com.fluxfr.tv.data.ProgressStore
import com.fluxfr.tv.data.Quality
import com.fluxfr.tv.data.RecentChannels
import com.fluxfr.tv.data.RecoItem
import com.fluxfr.tv.data.Saga
import com.fluxfr.tv.data.Sagas
import com.fluxfr.tv.data.Series
import com.fluxfr.tv.data.TmdbLists
import com.fluxfr.tv.data.Trending
import com.fluxfr.tv.data.VodMeta
import com.fluxfr.tv.player.PlayItem
import com.fluxfr.tv.player.Playables
import com.fluxfr.tv.player.StartMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ce que montre le grand en-tête : suit l'élément sélectionné. */
private data class Featured(
    val key: String,
    val eyebrow: String,
    /** Titre AFFICHÉ (nettoyé : [DisplayTitle.of]) ; l'année va dans la ligne d'infos. */
    val title: String,
    /** Ligne d'infos ([HeroMeta]) : texte libre en tête, année, genres, note brute. */
    val leading: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: String? = null,
    val image: String?,
    val quality: Quality?,
    val actionLabel: String,
    /** Film du catalogue mis en avant : aperçu animé (bande-annonce) après un temps d'arrêt. */
    val movie: Movie? = null,
    /** Chaîne en direct mise en avant : logo en grand en fond, aperçu en direct après un temps d'arrêt (TV). */
    val live: Channel? = null,
    /** Logo affiché en grand en fond (chaîne, match) quand il n'y a pas d'image. */
    val logo: String? = null,
    /** Téléphone : clé « Ma liste » et ouverture de la fiche depuis la grande carte. */
    val favKey: String? = null,
    val info: (() -> Unit)? = null,
    /** Tactile : titre du catalogue dont on cherche le visuel paysage TMDB (sinon [image] est une affiche portrait). */
    val tmdb: TmdbRef? = null,
    val action: () -> Unit,
)

/** Temps d'arrêt sur une vignette de film avant de lancer l'aperçu animé. */
private const val PREVIEW_DELAY_MS = 2_500L

/** Ce que l'aperçu de fond doit jouer (une seule chose à la fois : bande-annonce OU direct). */
private sealed class PreviewTarget(val id: String) {
    object None : PreviewTarget("")
    class Trailer(val movie: Movie) : PreviewTarget("m" + movie.id)
    class Live(val channel: Channel) : PreviewTarget("c" + channel.id)
}

/** Rangées de recommandations après dédoublonnage (dans l'ordre d'affichage de l'accueil). */
private class RecoRows(
    val forYou: List<ListEntry> = emptyList(),
    val because: List<Pair<String, List<ListEntry>>> = emptyList(),
    val genres: List<Pair<String, List<ListEntry>>> = emptyList(),
)

@Composable
fun HomeScreen(
    catalog: Catalog,
    onOpenSeries: (Series) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    /** Incrémenté à l'ouverture de l'appli ([com.fluxfr.tv.AppEvents.goHomeTop]) : retour en haut, focus sur l'en-tête. */
    scrollTopTick: Int = 0,
    /** Téléphone : appui sur l'avatar en haut à droite (choix du profil). */
    onOpenProfile: (() -> Unit)? = null,
    /** TV : incrémenté par l'appli quand plus rien n'a le focus (vignette retirée) : on le rend à l'accueil. */
    focusLostTick: Int = 0,
    /** Catalogue chargé en partie : petit avis non bloquant sous l'en-tête. */
    catalogIncomplete: Boolean = false,
) {
    val ctx = LocalContext.current
    val isTv = LocalIsTv.current
    val overlayOpen = LocalOverlayOpen.current
    val focusMemory = LocalFocusMemory.current
    val store = remember { ProgressStore.get(ctx) }
    val matches = rememberMatches(catalog)
    val version by ProgressStore.version.collectAsState()
    val profile by com.fluxfr.tv.data.Profiles.current.collectAsState()
    val activeProfile by com.fluxfr.tv.data.Profiles.active.collectAsState()
    // Synchro Drive de l'accueil (au plus toutes les 2 min, en arrière-plan) : à l'affichage et au retour.
    LaunchedEffect(Unit) { ProgressStore.onHomeShown(ctx) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ProgressStore.onHomeShown(ctx) }
    // En profil Enfants, on ne propose de reprendre que ce qui fait partie du catalogue jeunesse.
    // Lecture SQLite hors du thread principal ; la valeur précédente reste affichée pendant le rechargement,
    // mais jamais celle d'un autre profil.
    val resumeLoaded by produceState(Pair<String, List<Progress>>(profile, emptyList()), version, profile, activeProfile.kids, catalog) {
        val kids = activeProfile.kids
        val forProfile = profile
        val list = withContext(Dispatchers.IO) {
            val all = store.continueWatching()
            if (!kids) all
            else all.filter { p -> if (p.type == Progress.EPISODE) catalog.seriesById.containsKey(p.seriesId) else catalog.movieById.containsKey(p.itemId) }
        }
        value = forProfile to list
    }
    val resume = if (resumeLoaded.first == profile) resumeLoaded.second else emptyList()
    val movieProgressLoaded by produceState(Pair<String, Map<String, Progress>>(profile, emptyMap()), version, profile) {
        val forProfile = profile
        value = forProfile to withContext(Dispatchers.IO) { store.movies() }
    }
    val movieProgress = if (movieProgressLoaded.first == profile) movieProgressLoaded.second else emptyMap()

    // ---- Ouverture du lecteur : TOUJOURS par ici (l'aperçu en direct est fermé, et sa connexion libérée, avant).
    fun play(queue: List<PlayItem>, index: Int, mode: StartMode = StartMode.ASK) = playFromHome(ctx, queue, index, mode)

    val tnt = remember(catalog) { com.fluxfr.tv.data.Tnt.find(catalog) }
    // Chaîne TNT lancée d'une autre rangée (récentes, Ma liste…) : ▲▼ zappe dans la TNT, pas dans cette rangée.
    val tntIndexById = remember(tnt) { tnt.withIndex().associate { (i, t) -> t.channel.id to i } }
    fun playChannel(list: List<Channel>, i: Int) {
        val tntIdx = tntIndexById[list[i].id]
        if (tntIdx != null) play(Playables.tnt(tnt), tntIdx)
        else play(Playables.channels(list), i)
    }
    val newMovies = remember(catalog) { catalog.movies.take(40) }
    val uhdMovies = remember(catalog) { catalog.movies.filter { it.quality == Quality.UHD }.take(40) }
    val newSeries = remember(catalog) { catalog.series.take(40) }

    // « Chaînes récentes » et « Ma liste » : sur le catalogue complet (une catégorie masquée ne fait pas disparaître un favori).
    LaunchedEffect(Unit) {
        Favorites.load(ctx)
        RecentChannels.load(ctx)
    }
    val favKeys by Favorites.items.collectAsState()
    val recentIds by RecentChannels.ids.collectAsState()
    val channelById = remember(catalog) { catalog.unfiltered.channels.associateBy { it.id } }
    val recentChannels = remember(catalog, recentIds) { recentIds.mapNotNull { channelById[it] }.take(15) }
    // Rangée « Chaînes » : chaînes générales, sans les flux d'événements / bouche-trou (« PSG VS OM »,
    // « MULTI SCREEN »…) ni celles déjà montrées par les rangées TNT et récentes. Calcul hors du thread principal.
    val firstChannels by produceState(emptyList<Channel>(), catalog, tnt, recentIds) {
        value = withContext(Dispatchers.Default) {
            val skip = HashSet<String>().apply { tnt.forEach { add(it.channel.id) }; addAll(recentIds) }
            catalog.channels.asSequence()
                .filter { it.id !in skip && !HomeRows.isPlaceholderChannel(it.name, it.category) }
                .take(40).toList()
        }
    }
    val myList = remember(catalog, favKeys) {
        val all = catalog.unfiltered
        favKeys.mapNotNull { k ->
            val id = k.substringAfter(':')
            when (k.substringBefore(':')) {
                "movie" -> all.movieById[id]?.let { ListEntry.M(k, it) }
                "series" -> all.seriesById[id]?.let { ListEntry.S(k, it) }
                "live" -> channelById[id]?.let { ListEntry.C(k, it) }
                else -> null
            }
        }
    }
    val myListChannels = remember(myList) { myList.mapNotNull { (it as? ListEntry.C)?.ch } }

    // Top 10 France (AlloCiné), retrouvés dans le flux : chargé en arrière-plan, rangées masquées si indisponible.
    // Places réservées : pendant les 15 premières secondes, une rangée attendue mais encore en chargement garde sa
    // place (titre + vignettes grises) au lieu d'apparaître plus tard au-dessus de l'endroit où l'on est descendu.
    var loadWindow by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(15_000); loadWindow = false }

    // Paire (récupéré ?, titres) : la correspondance attend la fin de la récupération (null = pas encore là).
    val trending by produceState<Pair<Boolean, Trending.Titles?>>(false to null) {
        value = true to withContext(Dispatchers.IO) { runCatching { Trending.titles(ctx) }.getOrNull() }
    }
    // Correspondance calculée en arrière-plan : comparer 15 000 titres bloquerait l'interface. null = en cours.
    val topMoviesOrNull by produceState<List<Movie>?>(null, catalog, trending) {
        if (!trending.first) return@produceState
        value = withContext(Dispatchers.Default) {
            trending.second?.let { Trending.match(it.movies, catalog.movies, { m -> m.name }) }.orEmpty()
        }
    }
    val topSeriesOrNull by produceState<List<Series>?>(null, catalog, trending) {
        if (!trending.first) return@produceState
        value = withContext(Dispatchers.Default) {
            trending.second?.let { Trending.match(it.series, catalog.series, { s -> s.name }) }.orEmpty()
        }
    }
    val topMovies = topMoviesOrNull.orEmpty()
    val topSeries = topSeriesOrNull.orEmpty()

    // Tendances de la semaine (TMDB, cache 6 h), retrouvées dans le catalogue affiché ([TmdbLists]) :
    // réseau sur IO, correspondance sur Default, jamais sur le thread principal. Moins de 4 titres : rangée masquée.
    var trendMoviesDone by remember { mutableStateOf(false) }
    var trendSeriesDone by remember { mutableStateOf(false) }
    val trendMovies by produceState(TmdbLists.peekMovieTrending(catalog), catalog) {
        runCatching { TmdbLists.movieTrending(ctx, catalog) }.getOrNull()?.let { value = it }
        trendMoviesDone = true
    }
    val trendSeries by produceState(TmdbLists.peekSeriesTrending(catalog), catalog) {
        runCatching { TmdbLists.seriesTrending(ctx, catalog) }.getOrNull()?.let { value = it }
        trendSeriesDone = true
    }

    // Séries suivies avec un nouvel épisode.
    val newEpisodeIds = rememberNewEpisodeIds(catalog)
    val newEpisodeSeries = remember(catalog, newEpisodeIds) {
        if (newEpisodeIds.isEmpty()) emptyList() else catalog.series.filter { it.id in newEpisodeIds }
    }
    fun seriesBadge(id: String?) = if (id != null && id in newEpisodeIds) NEW_EPISODE_BADGE else null

    // Recommandations du profil (historique + TMDB), calculées hors du thread principal ([Reco]).
    // Sans historique : rangée « Pour toi » masquée (Top 10 et Tendances prennent le relais).
    // Dédoublonnage dans l'ordre d'affichage : un titre déjà montré plus haut (reprise, Top 10, tendances,
    // Ma liste, rangée de reco précédente) disparaît des rangées de reco suivantes ; moins de 6 titres : rangée masquée.
    val reco = rememberRecoRows(catalog, null)
    // Titres écartés (« Pas pour moi ») : masqués tout de suite, sans attendre le nouveau calcul.
    val ignoreVersion by com.fluxfr.tv.data.RecoIgnore.version.collectAsState()
    val ignoredSeeds = remember(profile, ignoreVersion) { com.fluxfr.tv.data.RecoIgnore.keys(ctx, profile) }
    val recoRows by produceState(
        RecoRows(), reco, resume, topMovies, topSeries, trendMovies, trendSeries, newEpisodeSeries, myList,
    ) {
        value = withContext(Dispatchers.Default) {
            val seen = HashSet<String>()
            fun mKeys(m: Movie) = listOf("m:" + m.id, HomeRows.titleKey('m', m.name))
            fun sKeys(s: Series) = listOf("s:" + s.id, HomeRows.titleKey('s', s.name))
            fun eKeys(e: ListEntry) = when (e) {
                is ListEntry.M -> mKeys(e.m)
                is ListEntry.S -> sKeys(e.s)
                is ListEntry.C -> listOf("c:" + e.ch.id)
            }
            for (p in resume) {
                if (p.type == Progress.EPISODE) { p.seriesId?.let { seen += "s:$it" }; seen += HomeRows.titleKey('s', p.title) }
                else { seen += "m:" + p.itemId; seen += HomeRows.titleKey('m', p.title) }
            }
            val forYou = HomeRows.dedupeRow(reco.forYou.map(::recoEntry), seen, ::eKeys)
            topMovies.forEach { seen += mKeys(it) }
            topSeries.forEach { seen += sKeys(it) }
            trendMovies.forEach { seen += mKeys(it) }
            trendSeries.forEach { seen += sKeys(it) }
            newEpisodeSeries.forEach { seen += sKeys(it) }
            myList.forEach { seen += eKeys(it) }
            val because = reco.because.mapNotNull { (seed, l) ->
                HomeRows.dedupeRow(l.map(::recoEntry), seen, ::eKeys).takeIf { it.isNotEmpty() }?.let { seed to it }
            }
            val genres = reco.genres.mapNotNull { (t, l) ->
                HomeRows.dedupeRow(l.map(::recoEntry), seen, ::eKeys).takeIf { it.isNotEmpty() }?.let { t to it }
            }
            RecoRows(forYou, because, genres)
        }
    }
    val forYou = recoRows.forYou

    var openSaga by remember { mutableStateOf<Saga?>(null) }
    var lastSagaKey by remember { mutableStateOf<String?>(null) }
    val sagaReturnFocus = remember { FocusRequester() }

    /**
     * [detail] : un film du catalogue ouvre sa fiche ; sinon (bouton « Reprendre » du héros) il est lancé directement,
     * en reprise directe (le choix est fait : pas de seconde invite dans le lecteur).
     */
    fun openProgress(p: Progress, detail: Boolean = true) {
        if (p.type == Progress.EPISODE && p.seriesId != null) {
            onOpenSeries(catalog.seriesById[p.seriesId] ?: Series(p.seriesId, p.title, "", p.poster, Quality.UNKNOWN, null, 0))
        } else {
            val movie = catalog.movieById[p.itemId]
            if (detail && movie != null) {
                onOpenMovie(movie)
            } else {
                val item = movie?.let(Playables::movie) ?: Playables.fromProgress(p)
                play(listOf(item), 0, if (p.finished) StartMode.ASK else StartMode.RESUME)
            }
        }
    }

    fun movieFeature(m: Movie, eyebrow: String): Featured {
        val meta = VodMeta.get(VodMeta.movieKey(m.id))
        val resumable = movieProgress[m.id]?.finished == false
        return Featured(
            "m${m.id}", eyebrow, HomeRows.shownTitle(m.name),
            year = DisplayTitle.year(m.name) ?: meta?.year,
            genres = HomeRows.heroGenres(meta?.genres, m.category),
            rating = m.rating,
            image = m.poster, quality = m.quality,
            actionLabel = if (resumable) "Reprendre" else "Lecture",
            movie = m,
            favKey = Favorites.movieKey(m.id),
            info = { onOpenMovie(m) },
            tmdb = TmdbRef.movie(m.id),
            // « Reprendre » : reprise directe (choix déjà fait) ; « Lecture » : comportement habituel.
        ) { play(listOf(Playables.movie(m)), 0, if (resumable) StartMode.RESUME else StartMode.ASK) }
    }

    fun seriesFeature(s: Series, eyebrow: String): Featured {
        val meta = VodMeta.get(VodMeta.seriesKey(s.id))
        return Featured(
            "s${s.id}", eyebrow, HomeRows.shownTitle(s.name),
            leading = "Série",
            year = DisplayTitle.year(s.name) ?: meta?.year,
            genres = HomeRows.heroGenres(meta?.genres, s.category),
            image = s.poster, quality = s.quality, actionLabel = "Voir les épisodes",
            favKey = Favorites.seriesKey(s.id),
            info = { onOpenSeries(s) },
            tmdb = TmdbRef.series(s.id),
        ) { onOpenSeries(s) }
    }

    /** Programme en cours (cache du guide, en mémoire : pas de réseau ni de disque). */
    fun nowOn(ch: Channel): String? = Epg.cached(ch.id).firstOrNull { it.isNow() }?.title

    fun channelFeature(ch: Channel, eyebrow: String, onPlay: () -> Unit) = Featured(
        "c${ch.id}", eyebrow, ch.name,
        leading = nowOn(ch),
        image = null, logo = ch.logo, quality = ch.quality, actionLabel = "Regarder",
        live = ch,
    ) { onPlay() }

    fun channelFeature(list: List<Channel>, i: Int) = channelFeature(list[i], "En direct") { playChannel(list, i) }

    fun matchFeature(m: Match) = Featured(
        "m" + m.key, m.eyebrow(), m.title,
        leading = m.meta(),
        image = null, logo = m.channel.logo, quality = null, actionLabel = m.actionLabel(),
        live = m.channel.takeIf { m.isLive },
    ) { activateMatch(ctx, catalog, m) }

    fun progressFeature(p: Progress): Featured {
        val movie = if (p.type == Progress.EPISODE) null else catalog.movieById[p.itemId]
        val meta = movie?.let { VodMeta.get(VodMeta.movieKey(it.id)) }
        return Featured(
            "p${p.key}", "Continuer à regarder", HomeRows.shownTitle(p.title),
            leading = when {
                p.type == Progress.EPISODE && p.finished -> "Épisode suivant"
                p.type == Progress.EPISODE -> p.subtitle
                p.duration > 0 -> "Reste " + formatDuration(p.duration - p.position)
                else -> null
            },
            year = if (p.type == Progress.EPISODE) null else DisplayTitle.year(p.title) ?: meta?.year,
            genres = if (movie != null) HomeRows.heroGenres(meta?.genres, movie.category) else emptyList(),
            rating = movie?.rating,
            image = p.poster, quality = null,
            actionLabel = if (p.type == Progress.EPISODE) "Continuer la série" else "Reprendre",
            movie = movie,
            tmdb = when {
                p.type == Progress.EPISODE -> p.seriesId?.let { TmdbRef.series(it) }
                movie != null -> TmdbRef.movie(movie.id)
                else -> null
            },
        ) { openProgress(p, detail = false) }
    }

    fun sagaFeature(s: Saga) = Featured(
        "saga-${s.key}", "Saga · ${s.movies.size} films", s.name,
        leading = s.movies.joinToString("  ·  ") { Sagas.yearFromName(it.name)?.toString() ?: DisplayTitle.of(it.name) }.take(120),
        image = s.movies.firstNotNullOfOrNull { it.poster }, quality = null, actionLabel = "Voir la saga",
    ) { openSaga = s }

    // ---- Menus d'actions des vignettes (appui long) : listes calculées à l'ouverture du menu.
    fun movieMenu(m: Movie, extra: List<CardAction> = emptyList()): () -> List<CardAction> = {
        movieActions(
            ctx, m, movieProgress[m.id],
            onInfo = { onOpenMovie(m) },
            play = { mode -> play(listOf(Playables.movie(m)), 0, mode) },
            extra = extra,
        )
    }

    fun seriesMenu(s: Series, extra: List<CardAction> = emptyList()): () -> List<CardAction> = {
        seriesActions(ctx, s, onOpen = { onOpenSeries(s) }, extra = extra)
    }

    /** « Continuer à regarder » : film → reprendre / depuis le début / fiche ; épisode → la série ; puis retirer. */
    fun resumeMenu(p: Progress): () -> List<CardAction> = {
        buildList {
            if (p.type == Progress.EPISODE && p.seriesId != null) {
                add(CardAction("Continuer la série", FluxIcons.Play, navigates = true) { openProgress(p) })
            } else {
                val movie = catalog.movieById[p.itemId]
                val item = movie?.let(Playables::movie) ?: Playables.fromProgress(p)
                if (p.finished) {
                    add(CardAction("Lecture", FluxIcons.Play, navigates = true) { play(listOf(item), 0, StartMode.ASK) })
                } else {
                    add(CardAction("Reprendre", FluxIcons.Play, navigates = true) { play(listOf(item), 0, StartMode.RESUME) })
                    add(CardAction("Lire depuis le début", FluxIcons.Refresh, navigates = true) { play(listOf(item), 0, StartMode.FROM_START) })
                }
                if (movie != null) add(CardAction("Plus d'infos", FluxIcons.Info, navigates = true) { onOpenMovie(movie) })
            }
            add(CardAction("Retirer de Continuer à regarder", FluxIcons.Close, danger = true) {
                store.remove(p)
                shortToast(ctx, "« ${HomeRows.shownTitle(p.title)} » retiré de Continuer à regarder")
            })
        }
    }

    val firstResumeKey = resume.firstOrNull()?.key
    val initial = remember(catalog, profile, firstResumeKey) {
        resume.firstOrNull()?.let(::progressFeature)
            ?: uhdMovies.firstOrNull()?.let { movieFeature(it, "À la une") }
            ?: newMovies.firstOrNull()?.let { movieFeature(it, "À la une") }
            ?: newSeries.firstOrNull()?.let { seriesFeature(it, "À la une") }
    }
    // Ne dépend pas du catalogue : une mise à jour en arrière-plan ne doit pas réinitialiser l'en-tête.
    // Fluidité : cet état n'est JAMAIS lu dans le corps de HomeScreen (seulement dans HeroHeader / HeroBackdrop
    // et à l'appel des lambdas) ; sinon chaque déplacement du focus recompose toutes les rangées.
    var focusedFeature by remember { mutableStateOf<Featured?>(null) }
    val initialState = rememberUpdatedState(initial)
    fun featuredNowValue(): Featured? = focusedFeature ?: initialState.value
    val heroFocus = remember { FocusRequester() }
    // TV : liste NON restaurée quand l'accueil est recréé (retour d'une autre section) : il s'ouvre en haut,
    // focus sur l'en-tête — jamais l'en-tête sélectionné au-dessus d'une liste restée défilée plus bas.
    val homeList: LazyListState = if (isTv) remember { rowsListState() } else rememberLazyListState()
    val rowNavScope = rememberCoroutineScope()
    val rowNav = rememberRowNav(homeList)
    // Lu seulement par HomeBackToTop (petite composition à part) et dans des callbacks.
    var heroFocused by remember { mutableStateOf(false) }
    var homeHasFocus by remember { mutableStateOf(false) }

    fun listAtTop() = homeList.firstVisibleItemIndex == 0 && homeList.firstVisibleItemScrollOffset == 0

    /** En-tête sélectionné ⇒ liste en haut, dans la même image (requestScrollToItem s'applique à la prochaine mesure). */
    fun onHeroFocus() {
        if (!listAtTop()) homeList.requestScrollToItem(0)
    }

    /** Retour en haut : liste remontée et focus sur le bouton de l'en-tête (quelques essais le temps de la recomposition). */
    suspend fun goTop(resetFeature: Boolean) {
        if (resetFeature) focusedFeature = null
        homeList.requestScrollToItem(0)
        repeat(6) {
            if (runCatching { heroFocus.requestFocus() }.getOrDefault(false)) return
            delay(40)
            homeList.requestScrollToItem(0)
        }
    }

    // Changement de profil : l'en-tête repart de la mise en avant du nouveau profil, liste en haut.
    var shownProfile by remember { mutableStateOf(profile) }
    LaunchedEffect(profile) {
        focusedFeature = null
        if (shownProfile != profile) {
            shownProfile = profile
            homeList.requestScrollToItem(0)
        }
    }
    // Focus initial sur le bouton du héros : seulement à la création de l'accueil (pas au retour du lecteur
    // ni à la fermeture d'une fiche, qui laisse l'accueil composé dessous). La liste est neuve (en haut).
    LaunchedEffect(Unit) { if (isTv) goTop(resetFeature = false) else runCatching { heroFocus.requestFocus() } }
    // Ouverture de l'appli (lanceur, longue absence) : saga fermée, liste remontée tout en haut, en-tête remis
    // sur sa mise en avant d'origine et focus sur son bouton. Gardé à travers les changements de section.
    var handledTopTick by rememberSaveable { mutableStateOf(scrollTopTick) }
    LaunchedEffect(scrollTopTick) {
        if (scrollTopTick == handledTopTick) return@LaunchedEffect
        handledTopTick = scrollTopTick
        openSaga = null
        lastSagaKey = null
        // Laisse la fiche / la saga se fermer et l'en-tête se recomposer : goTop réessaie quelques images.
        goTop(resetFeature = true)
    }
    // Focus perdu (vignette retirée : rangée recalculée au retour du lecteur…) : on le rend à la même vignette si
    // elle existe encore, sinon à la même rangée (sa dernière vignette), sinon en haut — jamais un état incohérent.
    var handledLostTick by remember { mutableStateOf(focusLostTick) }
    LaunchedEffect(focusLostTick) {
        if (focusLostTick == handledLostTick) return@LaunchedEffect
        handledLostTick = focusLostTick
        if (!isTv || openSaga != null) return@LaunchedEffect
        val back = focusMemory?.last
        if (back != null && runCatching { back.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        if (rowNav.focusRow(rowNav.lastRow)) {
            repeat(8) { withFrameNanos { } }
            if (homeHasFocus) return@LaunchedEffect
        }
        goTop(resetFeature = false)
    }

    // ---- Aperçus de fond (TV) : focus arrêté ≥ 2,5 s sur un film → bande-annonce ; sur une chaîne / un match en
    // direct → la chaîne en direct ([HomeLivePreview], un seul lecteur, une seule connexion).
    // Arrêtés dès que le focus change d'élément, quitte l'accueil (fiche, menu, saga) ou que l'appli passe
    // en arrière-plan (ON_STOP). Un seul aperçu à la fois ; la recherche en cours est annulée au changement.
    var appStarted by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { appStarted = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { appStarted = false; HomeLivePreview.stop() }
    if (isTv) DisposableEffect(Unit) { onDispose { HomeLivePreview.stop() } }
    var previewUrl by remember { mutableStateOf<String?>(null) }
    var previewShown by remember { mutableStateOf(false) }
    // Observé hors composition (snapshotFlow) : ne recompose pas l'accueil à chaque déplacement.
    LaunchedEffect(Unit) {
        snapshotFlow {
            val f = focusedFeature
            when {
                !(homeHasFocus && appStarted && openSaga == null) || f == null -> PreviewTarget.None
                f.movie != null -> PreviewTarget.Trailer(f.movie)
                f.live != null && isTv -> PreviewTarget.Live(f.live)
                else -> PreviewTarget.None
            }
        }
            .distinctUntilChangedBy { it.id }
            .collectLatest { t ->
                previewUrl = null
                previewShown = false
                // Tout changement de focus arrête l'aperçu en direct (une nouvelle chaîne repart du temps d'arrêt).
                HomeLivePreview.stop()
                when (t) {
                    is PreviewTarget.Live -> {
                        delay(HomeLivePreview.REST_MS)
                        HomeLivePreview.play(ctx, t.channel)
                    }
                    is PreviewTarget.Trailer -> {
                        val m = t.movie
                        delay(PREVIEW_DELAY_MS)
                        val url = withContext(Dispatchers.IO) {
                            runCatching {
                                val year = AllocineTrailer.cleanTitle(m.name).second ?: VodMeta.get(VodMeta.movieKey(m.id))?.year
                                AllocineTrailer.find(ctx, m.name, year)
                            }.getOrNull()
                        }
                        // Pas de repli YouTube pour l'aperçu : seulement les vidéos lisibles par ExoPlayer.
                        previewUrl = url?.takeUnless { "youtube" in it || "youtu.be" in it }
                    }
                    else -> Unit
                }
            }
    }

    // ---- Page d'une saga : par-dessus l'accueil ; Retour la ferme et rend le focus à sa vignette.
    // Téléphone : pas de focus ; le retour ferme la saga tant qu'aucune fiche n'est ouverte par-dessus.
    BackHandler(enabled = openSaga != null && (homeHasFocus || (!isTv && !overlayOpen))) { openSaga = null }
    LaunchedEffect(openSaga) {
        if (openSaga == null && lastSagaKey != null) {
            delay(30)
            runCatching { sagaReturnFocus.requestFocus() }
        }
    }
    // TV : Retour dans les rangées → d'abord en haut (en-tête sélectionné, liste en haut) ; ensuite le Retour
    // habituel (menu, puis quitter). Déclaré après ceux de l'appli : prioritaire.
    if (isTv) {
        HomeBackToTop(
            enabled = { homeHasFocus && !heroFocused && openSaga == null },
            onBack = { rowNavScope.launch { goTop(resetFeature = true) } },
        )
    }

    if (initial == null && catalog.channels.isEmpty()) {
        CenterMessage(
            "Aucun contenu français HD / 4K",
            "Le filtre n'a rien trouvé chez ton fournisseur. Vérifie les mots-clés dans Réglages.",
        )
        return
    }

    /** Vignette d'une rangée mêlant films et séries (recommandations). */
    @Composable
    fun MixedCard(e: ListEntry, eyebrow: String, extra: List<CardAction> = emptyList()) {
        when (e) {
            is ListEntry.M -> PosterCard(
                e.m.name, e.m.poster, e.m.quality,
                onClick = { onOpenMovie(e.m) },
                menu = movieMenu(e.m, extra),
                progress = movieProgress[e.m.id]?.takeUnless { it.finished }?.fraction,
                onFocus = { focusedFeature = movieFeature(e.m, eyebrow) },
            )
            is ListEntry.S -> PosterCard(
                e.s.name, e.s.poster, e.s.quality,
                onClick = { onOpenSeries(e.s) },
                menu = seriesMenu(e.s, extra),
                onFocus = { focusedFeature = seriesFeature(e.s, eyebrow) },
                badge = seriesBadge(e.s.id),
            )
            is ListEntry.C -> Unit
        }
    }

    // Rangées de l'accueil (communes à la TV et au téléphone).
    val homeShelves: LazyListScope.() -> Unit = {
                shelf(rowNav, "Continuer à regarder", resume, key = { it.key }) { p, _ ->
                    PosterCard(
                        title = p.title,
                        image = p.poster,
                        quality = null,
                        progress = if (p.finished) null else p.fraction,
                        subtitle = if (p.type == Progress.EPISODE) (if (p.finished) "Épisode suivant" else p.subtitle) else null,
                        onClick = { openProgress(p) },
                        menu = resumeMenu(p),
                        onFocus = { focusedFeature = progressFeature(p) },
                        badge = if (p.type == Progress.EPISODE) seriesBadge(p.seriesId) else null,
                    )
                }
                // 1. Recommandations du profil (TMDB).
                // Place réservée seulement si ce profil a un historique (sinon la rangée n'existe pas).
                shelf(rowNav, "Pour toi", forYou, key = { "foryou-" + it.key }, loading = loadWindow && resume.isNotEmpty()) { e, _ ->
                    MixedCard(e, "Pour toi")
                }
                // 2-3. Top 10 France.
                shelf(rowNav, "Top 10 des films en France", topMovies, key = { "top-" + it.id }, loading = loadWindow && topMoviesOrNull == null) { m, i ->
                    RankedPoster(i + 1) {
                        PosterCard(
                            m.name, m.poster, m.quality,
                            onClick = { onOpenMovie(m) },
                            menu = movieMenu(m),
                            progress = movieProgress[m.id]?.takeUnless { it.finished }?.fraction,
                            onFocus = { focusedFeature = movieFeature(m, "Top 10 France · n°${i + 1}") },
                        )
                    }
                }
                shelf(rowNav, "Top 10 des séries en France", topSeries, key = { "top-" + it.id }, loading = loadWindow && topSeriesOrNull == null) { se, i ->
                    RankedPoster(i + 1) {
                        PosterCard(
                            se.name, se.poster, se.quality,
                            onClick = { onOpenSeries(se) },
                            menu = seriesMenu(se),
                            onFocus = { focusedFeature = seriesFeature(se, "Top 10 France · n°${i + 1}") },
                            badge = seriesBadge(se.id),
                        )
                    }
                }
                // 4-5. Tendances de la semaine (TMDB).
                shelf(rowNav, "Tendances films", trendMovies, key = { "trendm-" + it.id }, loading = loadWindow && !trendMoviesDone) { m, _ ->
                    PosterCard(
                        m.name, m.poster, m.quality,
                        onClick = { onOpenMovie(m) },
                        menu = movieMenu(m),
                        progress = movieProgress[m.id]?.takeUnless { it.finished }?.fraction,
                        onFocus = { focusedFeature = movieFeature(m, "Tendances films") },
                    )
                }
                shelf(rowNav, "Tendances séries", trendSeries, key = { "trends-" + it.id }, loading = loadWindow && !trendSeriesDone) { s, _ ->
                    PosterCard(
                        s.name, s.poster, s.quality,
                        onClick = { onOpenSeries(s) },
                        menu = seriesMenu(s),
                        onFocus = { focusedFeature = seriesFeature(s, "Tendances séries") },
                        badge = seriesBadge(s.id),
                    )
                }
                shelf(rowNav, "Nouveaux épisodes", newEpisodeSeries, key = { "newep-" + it.id }) { s, _ ->
                    PosterCard(
                        s.name, s.poster, s.quality,
                        onClick = { onOpenSeries(s) },
                        menu = seriesMenu(s),
                        onFocus = { focusedFeature = seriesFeature(s, "Nouvel épisode") },
                        badge = NEW_EPISODE_BADGE,
                    )
                }
                shelf(rowNav, "Chaînes récentes", recentChannels, key = { "recent-" + it.id }) { ch, i ->
                    ChannelCard(
                        ch.name, ch.logo, ch.quality,
                        onClick = { playChannel(recentChannels, i) },
                        onFocus = { focusedFeature = channelFeature(recentChannels, i) },
                        channelId = ch.id,
                        onLongClick = { toggleChannelFavorite(ctx, ch.id) },
                        favorite = Favorites.liveKey(ch.id) in favKeys,
                    )
                }
                shelf(rowNav, "Ma liste", myList, key = { "fav-" + it.key }) { e, _ ->
                    when (e) {
                        is ListEntry.M -> PosterCard(
                            e.m.name, e.m.poster, e.m.quality,
                            onClick = { onOpenMovie(e.m) },
                            menu = movieMenu(e.m),
                            progress = movieProgress[e.m.id]?.takeUnless { it.finished }?.fraction,
                            onFocus = { focusedFeature = movieFeature(e.m, "Ma liste") },
                        )
                        is ListEntry.S -> PosterCard(
                            e.s.name, e.s.poster, e.s.quality,
                            onClick = { onOpenSeries(e.s) },
                            menu = seriesMenu(e.s),
                            onFocus = { focusedFeature = seriesFeature(e.s, "Ma liste") },
                            badge = seriesBadge(e.s.id),
                        )
                        is ListEntry.C -> {
                            val idx = myListChannels.indexOf(e.ch).coerceAtLeast(0)
                            ChannelCard(
                                e.ch.name, e.ch.logo, e.ch.quality,
                                onClick = { playChannel(myListChannels, idx) },
                                onFocus = { focusedFeature = channelFeature(myListChannels, idx) },
                                channelId = e.ch.id,
                                onLongClick = { toggleChannelFavorite(ctx, e.ch.id) },
                                favorite = true,
                            )
                        }
                    }
                }
                // « Parce que tu as regardé « X » » (titres récemment regardés par ce profil).
                for ((seed, items) in recoRows.because) {
                    if (com.fluxfr.tv.data.RecoIgnore.matches(ignoredSeeds, seed)) continue
                    val shown = DisplayTitle.of(seed)
                    val title = "Parce que tu as regardé « $shown »"
                    shelf(
                        rowNav, title, items, key = { "because-" + it.key },
                        // « Pas pour moi » : ce titre ne sert plus aux recommandations de ce profil.
                        footer = {
                            NotForMeCard(shown) {
                                com.fluxfr.tv.data.RecoIgnore.add(ctx, profile, seed)
                                android.widget.Toast.makeText(
                                    ctx, "« $shown » ne servira plus à tes recommandations", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    ) { e, _ ->
                        MixedCard(
                            e, title,
                            extra = listOf(
                                CardAction("Je n'ai pas regardé « $shown »", FluxIcons.Block) {
                                    com.fluxfr.tv.data.RecoIgnore.add(ctx, profile, seed)
                                    shortToast(ctx, "« $shown » ne servira plus à tes recommandations")
                                },
                            ),
                        )
                    }
                }
                // « Comédies pour toi » : genre préféré du profil.
                for ((title, items) in recoRows.genres) {
                    shelf(rowNav, title, items, key = { "genre-" + it.key }) { e, _ -> MixedCard(e, title) }
                }
                // Puis matchs du jour, TNT et chaînes.
                matchesShelf(matches, catalog, nav = rowNav, onFocus = { m -> focusedFeature = matchFeature(m) })
                shelf(rowNav, "TNT", tnt, key = { it.number }) { t, i ->
                    ChannelCard(
                        "${t.number}. ${t.channel.name}", t.channel.logo, t.channel.quality,
                        onClick = { play(Playables.tnt(tnt), i) },
                        channelId = t.channel.id,
                        onLongClick = { toggleChannelFavorite(ctx, t.channel.id) },
                        favorite = Favorites.liveKey(t.channel.id) in favKeys,
                        onFocus = {
                            focusedFeature = channelFeature(t.channel, "TNT · chaîne ${t.number}") { play(Playables.tnt(tnt), i) }
                                .copy(key = "t${t.number}")
                        },
                    )
                }
                shelf(rowNav, "Chaînes", firstChannels, key = { it.id }) { ch, i ->
                    ChannelCard(
                        ch.name, ch.logo, ch.quality,
                        onClick = { play(Playables.channels(firstChannels), i) },
                        onFocus = {
                            focusedFeature = channelFeature(ch, "En direct") { play(Playables.channels(firstChannels), i) }
                        },
                        channelId = ch.id,
                        onLongClick = { toggleChannelFavorite(ctx, ch.id) },
                        favorite = Favorites.liveKey(ch.id) in favKeys,
                    )
                }
    }

    // ---- Téléphone / tablette : grande carte « à la une » fixe (pas d'en-tête qui suit le focus, pas
    // d'aperçu vidéo), puis les mêmes rangées, balayables au doigt.
    if (!isTv) {
        val compact = rememberIsCompact()
        val featured = remember(forYou, topMovies, topSeries, initial) {
            when (val e = forYou.firstOrNull()) {
                is ListEntry.M -> movieFeature(e.m, "Pour toi")
                is ListEntry.S -> seriesFeature(e.s, "Pour toi")
                else -> null
            }
                ?: topMovies.firstOrNull()?.let { movieFeature(it, "Top 10 France · n°1") }
                ?: topSeries.firstOrNull()?.let { seriesFeature(it, "Top 10 France · n°1") }
                ?: initial
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = homeList,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                listTopAnchor(rowNav)
                // Tablette / paysage : le rail de navigation montre déjà le logo et l'avatar (pas de doublon).
                if (compact) {
                    item(key = "__phone_top", contentType = "top") {
                        PhoneTopBar(activeProfile, onOpenProfile)
                    }
                } else {
                    item(key = "__phone_top_space", contentType = "top") { Spacer(Modifier.height(4.dp)) }
                }
                if (featured != null) {
                    item(key = "__phone_hero", contentType = "hero") {
                        val f = featured
                        PhoneHeroCard(
                            eyebrow = f.eyebrow, title = f.title,
                            meta = HomeRows.metaText(f.leading, f.year, f.genres, f.rating),
                            image = f.image, quality = f.quality,
                            primaryLabel = f.actionLabel, onPrimary = f.action,
                            secondary = f.favKey?.let { k -> { m -> MyListButton(k, m) } },
                            onImageClick = f.info ?: f.action,
                            tmdb = f.tmdb,
                        )
                    }
                }
                if (catalogIncomplete) item(key = "__phone_incomplete", contentType = "notice") { IncompleteNotice() }
                item(key = "__phone_update", contentType = "update") { UpdateBanner() }
                homeShelves()
            }
            openSaga?.let { s ->
                Box(Modifier.fillMaxSize().blockTouches()) {
                    SagaPage(
                        s,
                        movieProgress = movieProgress,
                        onOpenMovie = onOpenMovie,
                        onPlay = { m, mode -> play(listOf(Playables.movie(m)), 0, mode) },
                    )
                }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().onFocusChanged { homeHasFocus = it.hasFocus }) {
        HeroBackdrop(
            feature = ::featuredNowValue,
            previewUrl = { previewUrl },
            previewShown = { previewShown },
            onFailed = { v -> if (previewUrl == v) { previewUrl = null; previewShown = false } },
            onStarted = { v -> if (previewUrl == v) previewShown = true },
        )

        Column(
            Modifier.fillMaxSize()
                // Page de saga ouverte : l'accueil reste composé dessous mais ne prend pas le focus.
                .focusProperties { enter = { if (openSaga != null) FocusRequester.Cancel else FocusRequester.Default } }
                .focusGroup()
        ) {
            // ---- Nouvelle version : tout en haut, bien visible (▲ depuis le bouton de l'en-tête)
            UpdateBanner(top = true)
            // ---- En-tête immersif
            HeroHeader(
                feature = ::featuredNowValue,
                heroFocus = heroFocus,
                onFocusChanged = { f -> heroFocused = f; if (f) onHeroFocus() },
            )
            if (catalogIncomplete) IncompleteNotice()

            // ---- Rangées
            CompositionLocalProvider(LocalBringIntoViewSpec provides NoVerticalScrollSpec) {
            LazyColumn(
                state = homeList,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = 0.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                listTopAnchor(rowNav)
                homeShelves()
                listBottomRoom()
            }
            }
        }

        openSaga?.let { s ->
            SagaPage(
                s,
                movieProgress = movieProgress,
                onOpenMovie = onOpenMovie,
                onPlay = { m, mode -> play(listOf(Playables.movie(m)), 0, mode) },
            )
        }
    }
}

/** Retour → haut de l'accueil ; composition à part : l'état lu ici ne recompose pas tout l'accueil. */
@Composable
private fun HomeBackToTop(enabled: () -> Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled()) { onBack() }
}

/** Avis discret : catalogue chargé en partie (nouvel essai automatique). */
@Composable
private fun IncompleteNotice() {
    Text(
        "Catalogue incomplet : nouvel essai dans quelques minutes",
        fontSize = 12.sp, color = Palette.Faint, maxLines = 1,
        modifier = Modifier.padding(start = screenPadStart(), bottom = 6.dp),
    )
}

/** Page d'une saga : ses films dans l'ordre de sortie, en grille. OK ouvre la fiche du film. */
@Composable
private fun SagaPage(
    saga: Saga,
    movieProgress: Map<String, Progress>,
    onOpenMovie: (Movie) -> Unit,
    onPlay: (Movie, StartMode) -> Unit,
) {
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    var backdrop by remember(saga.key) { mutableStateOf(saga.movies.firstNotNullOfOrNull { it.poster }) }
    LaunchedEffect(saga.key) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Backdrop(backdrop)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(
                when {
                    LocalIsTv.current -> 132.dp
                    rememberIsCompact() -> 110.dp
                    else -> 144.dp
                }
            ),
            modifier = Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(start = screenPadStart(), end = screenPadEnd(), top = if (LocalIsTv.current) 34.dp else 16.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) 18.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "__header", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(
                        "SAGA", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = Palette.Accent, letterSpacing = 1.5.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(saga.name, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text("${saga.movies.size} films", fontSize = 13.sp, color = Palette.Dim)
                }
            }
            items(saga.movies.size, key = { saga.movies[it].id }) { i ->
                val m = saga.movies[i]
                PosterCard(
                    m.name, m.poster, m.quality,
                    onClick = { onOpenMovie(m) },
                    menu = { movieActions(ctx, m, movieProgress[m.id], onInfo = { onOpenMovie(m) }, play = { mode -> onPlay(m, mode) }) },
                    modifier = Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(first) else it },
                    progress = movieProgress[m.id]?.takeUnless { it.finished }?.fraction,
                    subtitle = Sagas.yearFromName(m.name)?.toString(),
                    onFocus = { backdrop = m.poster ?: backdrop },
                )
            }
        }
    }
}

/** Un élément de « Ma liste » ([key] = clé Favorites). */
private sealed class ListEntry(val key: String) {
    class M(key: String, val m: Movie) : ListEntry(key)
    class S(key: String, val s: Series) : ListEntry(key)
    class C(key: String, val ch: Channel) : ListEntry(key)
}

private fun recoEntry(r: RecoItem): ListEntry = when (r) {
    is RecoItem.M -> ListEntry.M(Favorites.movieKey(r.movie.id), r.movie)
    is RecoItem.S -> ListEntry.S(Favorites.seriesKey(r.series.id), r.series)
}

/**
 * Valeur de [source] avec un court délai : en défilement rapide, seule la dernière vignette s'affiche
 * (pas de fondu relancé à chaque cran). La lecture de l'état se fait ici, pas dans l'écran parent.
 */
@Composable
private fun <T> rememberSettled(delayMs: Long, source: () -> T): T {
    val latest by rememberUpdatedState(source)
    return produceState(initialValue = source()) {
        snapshotFlow { latest() }.collectLatest { v ->
            if (v != value) { delay(delayMs); value = v }
        }
    }.value
}

/** En-tête de l'accueil (titre, infos, bouton) : seul ce bloc se recompose quand le focus change. */
@Composable
private fun HeroHeader(feature: () -> Featured?, heroFocus: FocusRequester, onFocusChanged: (Boolean) -> Unit) {
    val f = rememberSettled(110, feature)
    Box(Modifier.fillMaxWidth().height(232.dp).padding(start = 40.dp, top = 34.dp, end = 300.dp)) {
        if (f != null) {
            AnimatedContent(
                targetState = f,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                contentKey = { it.key },
                label = "hero",
            ) { item ->
                Column {
                    Text(
                        item.eyebrow.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = Palette.Accent, letterSpacing = 1.5.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        item.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, lineHeight = 34.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    HeroMeta(
                        year = item.year, genres = item.genres, rating = item.rating,
                        leading = item.leading, quality = item.quality,
                    )
                }
            }
            ActionButton(
                f.actionLabel, f.action,
                Modifier.align(Alignment.BottomStart).padding(bottom = 14.dp).focusRequester(heroFocus)
                    .onFocusChanged { onFocusChanged(it.isFocused) },
                primary = true, icon = FluxIcons.Play,
            )
        }
    }
}

/** Ce que montre le fond : image, ou logo de chaîne (direct / match), et chaîne pouvant être prévisualisée. */
private data class BackdropTarget(val image: String?, val logo: String?, val liveId: String?)

/**
 * Fond de l'accueil : image du programme sélectionné (changée seulement à l'arrêt), logo de la chaîne en grand
 * sur un dégradé neutre (direct, match), ou aperçu vidéo (bande-annonce, ou direct via [HomeLivePreview]).
 */
@Composable
private fun HeroBackdrop(
    feature: () -> Featured?,
    previewUrl: () -> String?,
    previewShown: () -> Boolean,
    onFailed: (String) -> Unit,
    onStarted: (String) -> Unit,
) {
    val target = rememberSettled(320) { feature()?.let { BackdropTarget(it.image, it.logo, it.live?.id) } }
    val live by HomeLivePreview.session.collectAsState()
    val video = previewUrl()
    val liveId = target?.liveId?.takeIf { live?.channelId == it }
    val liveRendered = liveId != null && live?.rendered == true
    Box(Modifier.fillMaxSize()) {
        Backdrop(
            // Vidéo affichée : elle remplace l'image fixe (fondu).
            if ((previewShown() && video != null) || liveRendered) null else target?.image,
            video = when {
                video != null -> {
                    {
                        key(video) {
                            TrailerVideo(
                                video, muted = false, modifier = Modifier.fillMaxSize(),
                                onFailed = { onFailed(video) },
                                onStarted = { onStarted(video) },
                            )
                        }
                    }
                }
                liveId != null -> { { HomeLiveVideo(liveId, Modifier.fillMaxSize()) } }
                else -> null
            },
        )
        val logo = target?.logo?.takeIf { target.image == null && !liveRendered }
        LiveLogoBackdrop(logo)
    }
}

/** Logo de chaîne en grand, à droite, sur un dégradé neutre (à la place de l'image du film précédent). */
@Composable
private fun BoxScope.LiveLogoBackdrop(logo: String?) {
    Crossfade(
        targetState = logo, animationSpec = tween(250), label = "liveLogo",
        modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.68f).fillMaxHeight(0.55f),
    ) { l ->
        if (l != null) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(Color(0xFF2A3150).copy(alpha = 0.75f), Color.Transparent))
                ),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = l, contentDescription = null, contentScale = ContentScale.Fit, alpha = 0.92f,
                    modifier = Modifier.size(width = 300.dp, height = 170.dp),
                )
            }
        }
    }
}

/** Bandeau « nouvelle version disponible sur l'autre TV » (mise à jour par le réseau local). */
@Composable
private fun UpdateBanner(top: Boolean = false) {
    val ctx = LocalContext.current
    val info by com.fluxfr.tv.data.Updater.available.collectAsState()
    val state by com.fluxfr.tv.data.Updater.state.collectAsState()
    val u = info ?: return
    Row(
        Modifier.padding(start = screenPadStart(), top = if (top) 18.dp else 0.dp, bottom = 6.dp)
            .background(Palette.Accent.copy(alpha = 0.18f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = when (val s = state) {
            is com.fluxfr.tv.data.UpdateState.Downloading -> "Téléchargement… ${(s.progress * 100).toInt().coerceAtLeast(0)} %"
            else -> if (u.sha256 != null) "Nouvelle version ${u.versionName} disponible"
            else "Nouvelle version ${u.versionName} disponible sur ${u.device}"
        }
        Text(label, fontSize = 13.sp, color = Color.White)
        Spacer(Modifier.width(12.dp))
        Pill("Installer", selected = false, onClick = {
            if (state is com.fluxfr.tv.data.UpdateState.NeedsPermission) com.fluxfr.tv.data.Updater.openInstallPermissionSettings(ctx)
            else com.fluxfr.tv.data.Updater.installAsync(ctx, u)
        })
    }
}

/** Raccourci vers une appli de replay : sa bannière Android TV, au format des cartes de chaînes. */
@Suppress("unused")
@Composable
private fun ReplayCard(app: com.fluxfr.tv.data.ReplayApps.App, onClick: () -> Unit, onFocus: () -> Unit) {
    val image = remember(app.pkg) { app.banner?.asImageBitmap() }
    Column(Modifier.revealOnFocus().width(176.dp)) {
        FocusSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onFocus() },
            containerColor = Palette.CardHigh,
            focusedContainerColor = Color(0xFF2E3550),
        ) {
            if (image != null) {
                androidx.compose.foundation.Image(
                    image, app.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    app.name, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(app.name, fontSize = 13.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Vignette avec grand numéro de classement à gauche, façon « Top 10 ». */
@Composable
private fun RankedPoster(rank: Int, poster: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Bottom) {
        if (LocalIsTv.current) {
            Text(
                rank.toString(),
                fontSize = 96.sp, fontWeight = FontWeight.Bold, lineHeight = 96.sp,
                color = Color.White.copy(alpha = 0.16f),
                style = androidx.compose.ui.text.TextStyle(
                    drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f),
                ),
                modifier = Modifier.padding(bottom = 28.dp).offset(x = 14.dp),
            )
        } else {
            // Tactile : numéro lisible sur le fond sombre (remplissage léger + contour clair).
            Box(Modifier.padding(bottom = 28.dp).offset(x = 14.dp)) {
                Text(
                    rank.toString(), fontSize = 96.sp, fontWeight = FontWeight.Bold, lineHeight = 96.sp,
                    color = Color.White.copy(alpha = 0.14f),
                )
                Text(
                    rank.toString(), fontSize = 96.sp, fontWeight = FontWeight.Bold, lineHeight = 96.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    style = androidx.compose.ui.text.TextStyle(
                        drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f),
                    ),
                )
            }
        }
        poster()
    }
}
