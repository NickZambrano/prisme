package com.fluxfr.tv.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.fluxfr.tv.BuildConfig
import com.fluxfr.tv.data.AllocineTrailer
import com.fluxfr.tv.data.Source
import com.fluxfr.tv.player.PlayItem
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.fluxfr.tv.data.Favorites
import com.fluxfr.tv.data.AvFormat
import com.fluxfr.tv.data.CatalogMatcher
import com.fluxfr.tv.data.CatalogRepository
import com.fluxfr.tv.data.DisplayTitle
import com.fluxfr.tv.data.Tmdb
import com.fluxfr.tv.data.TmdbParse
import com.fluxfr.tv.data.VodMeta
import com.fluxfr.tv.data.HiddenCategories
import com.fluxfr.tv.data.Profiles
import com.fluxfr.tv.data.Sagas
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.focusRestorer
import com.fluxfr.tv.data.Movie
import com.fluxfr.tv.data.MovieInfo
import com.fluxfr.tv.data.Progress
import com.fluxfr.tv.data.ProgressStore
import com.fluxfr.tv.data.Quality
import com.fluxfr.tv.data.Settings
import com.fluxfr.tv.data.XtreamClient
import com.fluxfr.tv.player.Playables
import com.fluxfr.tv.player.PlayerSession
import com.fluxfr.tv.player.StartMode
import androidx.compose.foundation.layout.navigationBarsPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Délai avant de lancer la bande-annonce en fond (façon Netflix). */
private const val TRAILER_DELAY_MS = 2_500L
/** Si la vidéo n'a pas démarré dans ce délai, on garde l'image. */
private const val TRAILER_TIMEOUT_MS = 20_000L
/** Part de la largeur occupée par le visuel (à droite). */
private const val ART_FRACTION = 0.70f
/** Largeur maximale des textes de la colonne d'informations. */
private val TEXT_MAX_WIDTH = 420.dp
/** Attente maximale de la recherche AlloCiné après le délai, avant de passer à YouTube. */
private const val ALLOCINE_WAIT_MS = 15_000L

private enum class TrailerMode { NONE, ALLOCINE, YOUTUBE }

private fun trailerLog(msg: String) {
    if (BuildConfig.DEBUG) Log.d("FluxTrailer", msg)
}

@Composable
fun MovieDetailScreen(
    movie: Movie,
    settings: Settings,
    /** Porté par le bouton principal (Lecture / Reprendre) : reçoit le focus. */
    primaryFocus: FocusRequester = remember { FocusRequester() },
    /** Téléphone : flèche de retour en haut de la fiche. */
    onBack: (() -> Unit)? = null,
) {
    // Un film de la rangée « Dans la même saga » remplace la fiche affichée (état remis à zéro par key).
    var shown by remember(movie.id) { mutableStateOf(movie) }
    key(shown.id) {
        MovieDetailContent(shown, settings, primaryFocus, onOpenMovie = { shown = it }, onBack = onBack)
    }
}

/** Films de la même saga (visibles dans le profil), avec le nom de la saga ; null si aucune. */
@Composable
private fun rememberSagaOf(movie: Movie): Pair<String, List<Movie>>? {
    val ctx = LocalContext.current.applicationContext
    val hidden by HiddenCategories.hidden.collectAsState()
    val activeProfile by Profiles.active.collectAsState()
    val kids = activeProfile.kids
    val saga by produceState<Pair<String, List<Movie>>?>(null, movie.id, hidden, kids) {
        value = withContext(Dispatchers.Default) {
            val catalog = (CatalogRepository.state.value as? CatalogRepository.State.Ready)?.catalog
                ?: return@withContext null
            val s = Sagas.index(catalog.movies, ctx).byMovieId[movie.id] ?: return@withContext null
            val films = s.movies.filter {
                it.id == movie.id || (HiddenCategories.key(HiddenCategories.MOVIE, it.category) !in hidden &&
                    (!kids || Profiles.isKids(it.category)))
            }
            if (films.size < 2) null else s.name to films
        }
    }
    return saga
}

/**
 * « Titres similaires » : recommandations TMDB du film (identifiant TMDB déjà connu, réponse en cache 7 jours,
 * au plus 2 requêtes), retrouvées dans le catalogue ; complétées par les films récents de la même catégorie.
 * Hors du thread principal ; jamais de titre masqué (catégories masquées, profil Enfants).
 */
@Composable
private fun rememberSimilarMovies(movie: Movie): List<Movie> {
    val ctx = LocalContext.current.applicationContext
    val hidden by HiddenCategories.hidden.collectAsState()
    val activeProfile by Profiles.active.collectAsState()
    val kids = activeProfile.kids
    val similar by produceState(emptyList<Movie>(), movie.id, hidden, kids) {
        value = withContext(Dispatchers.IO) {
            val catalog = (CatalogRepository.state.value as? CatalogRepository.State.Ready)?.catalog
                ?: return@withContext emptyList()
            VodMeta.load(ctx)
            fun visible(m: Movie) = m.id != movie.id &&
                HiddenCategories.key(HiddenCategories.MOVIE, m.category) !in hidden &&
                (!kids || Profiles.isKids(m.category))
            val key = VodMeta.movieKey(movie.id)
            val tmdbId = VodMeta.get(key)?.tmdbId?.trim()?.takeIf { (it.toLongOrNull() ?: 0) > 0 }
                ?: Tmdb.resolvedId(ctx, key)
            var found: List<Movie> = emptyList()
            if (tmdbId != null) {
                found = runCatching {
                    val json = Tmdb.get(Tmdb.Session(ctx, refreshStale = true, maxRequests = 2), "/movie/$tmdbId/recommendations")
                    CatalogMatcher.of(catalog).movies(TmdbParse.hits(json, Tmdb.MOVIE), 24).filter(::visible)
                }.getOrDefault(emptyList())
            }
            if (found.size < 8) {
                val have = found.mapTo(HashSet()) { it.id }
                val fill = catalog.moviesByCat[movie.category].orEmpty()
                    .filter { visible(it) && it.id !in have && it.quality != Quality.LOW }
                    .sortedByDescending { it.added }
                    .take(24 - found.size)
                found = found + fill
            }
            found
        }
    }
    return similar
}

@Composable
private fun MovieDetailContent(
    movie: Movie,
    settings: Settings,
    primaryFocus: FocusRequester,
    onOpenMovie: (Movie) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val saga = rememberSagaOf(movie)
    val similar = rememberSimilarMovies(movie)
    val hasBelow = saga != null || similar.size >= 3
    val title = remember(movie.name) { HomeRows.shownTitle(movie.name) }
    val store = remember { ProgressStore.get(ctx) }
    var info by remember(movie.id) { mutableStateOf<MovieInfo?>(null) }

    LaunchedEffect(movie.id) {
        if (settings.mode != Settings.MODE_XTREAM) return@LaunchedEffect
        info = try {
            withContext(Dispatchers.IO) {
                XtreamClient(settings.server, settings.user, settings.pass).vodInfo(movie.id)
            }
        } catch (e: Exception) {
            null // Pas d'infos supplémentaires : on garde l'affiche et le titre.
        }
    }

    val version by ProgressStore.version.collectAsState()
    // SQLite hors du thread principal ; l'ancienne valeur reste affichée pendant le rechargement.
    val progressLoaded by produceState(Pair<String, Progress?>(movie.id, null), version, movie.id) {
        val id = movie.id
        value = id to withContext(Dispatchers.IO) { store.get(Progress.movieKey(id)) }
    }
    val progress = if (progressLoaded.first == movie.id) progressLoaded.second else null
    val resumable = progress != null && !progress.finished && progress.position > 10_000

    LaunchedEffect(movie.id) { runCatching { primaryFocus.requestFocus() } }

    // ---- Bande-annonce en fond : seulement quand l'écran est au premier plan (pas sous le lecteur).
    // ON_PAUSE (et non ON_STOP) : le son doit se couper dès qu'une autre activité passe devant.
    // Source principale : bande-annonce française AlloCiné (mp4 lu par ExoPlayer) ;
    // repli : l'intégration YouTube (souvent refusée par les studios).
    val trailerId = info?.trailerYoutubeId
    var visible by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { visible = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { visible = false }
    var trailerMode by remember(movie.id) { mutableStateOf(TrailerMode.NONE) }

    // Recherche AlloCiné lancée dès l'ouverture, pour être prête à la fin du délai.
    var allocineUrl by remember(movie.id) { mutableStateOf<String?>(null) }
    var allocineDone by remember(movie.id) { mutableStateOf(false) }
    var allocineFailed by remember(movie.id) { mutableStateOf(false) }
    LaunchedEffect(movie.id) {
        val titleYear = AllocineTrailer.cleanTitle(movie.name).second
        // Sans année dans le titre : on attend brièvement celle de la fiche Xtream.
        val year = titleYear ?: if (settings.mode == Settings.MODE_XTREAM) {
            withTimeoutOrNull(TRAILER_DELAY_MS) { snapshotFlow { info }.filterNotNull().first() }
                ?.year?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
        } else null
        allocineUrl = withContext(Dispatchers.IO) { AllocineTrailer.find(ctx, movie.name, year) }
        allocineDone = true
    }

    /**
     * [mode] : choix déjà fait sur la fiche — « Reprendre à … » → reprise directe ([StartMode.RESUME]),
     * « Depuis le début » → [StartMode.FROM_START] ; « Lecture » sans historique → [StartMode.ASK].
     */
    fun play(mode: StartMode = StartMode.ASK) {
        trailerMode = TrailerMode.NONE // Coupe (et détruit) la bande-annonce avant de lancer le film.
        PlayerSession.start(ctx, listOf(Playables.movie(movie)), 0, mode)
    }
    val playMode = if (resumable) StartMode.RESUME else StartMode.ASK
    /** « Bande-annonce » : AlloCiné dans le lecteur, sinon l'appli YouTube. */
    fun openTrailer(mp4: String?) {
        if (mp4 != null) {
            trailerMode = TrailerMode.NONE // Coupe la bande-annonce de fond.
            PlayerSession.start(
                ctx,
                listOf(
                    PlayItem(
                        title, "Bande-annonce", listOf(Source(mp4, Quality.UNKNOWN)),
                        live = false, progressKey = null, type = "trailer",
                        itemId = "trailer:${movie.id}", poster = movie.poster,
                    )
                ),
                0,
            )
        } else if (trailerId != null) {
            openYoutube(ctx, trailerId)
        }
    }
    var trailerFailed by remember(movie.id) { mutableStateOf(false) }
    LaunchedEffect(movie.id, visible, allocineFailed, trailerFailed) {
        trailerMode = TrailerMode.NONE
        if (!visible) return@LaunchedEffect
        delay(TRAILER_DELAY_MS)
        withTimeoutOrNull(ALLOCINE_WAIT_MS) { snapshotFlow { allocineDone }.first { it } }
        if (allocineUrl != null && !allocineFailed) {
            trailerLog("AlloCiné : ${movie.name} → $allocineUrl")
            trailerMode = TrailerMode.ALLOCINE
            return@LaunchedEffect
        }
        if (trailerFailed) return@LaunchedEffect
        // Repli YouTube : l'identifiant vient de la fiche, qui peut encore être en chargement.
        val id = withTimeoutOrNull(TRAILER_TIMEOUT_MS) {
            snapshotFlow { info }.filterNotNull().first().trailerYoutubeId
        }
        if (id == null) {
            trailerLog("${movie.name} : ni AlloCiné${if (allocineFailed) " (erreur de lecture)" else ""} ni YouTube → image")
            return@LaunchedEffect
        }
        trailerLog("YouTube : ${movie.name} → $id")
        trailerMode = TrailerMode.YOUTUBE
    }

    val image = info?.backdrop ?: movie.poster ?: info?.cover

    // ---- Téléphone / tablette : fiche verticale qui défile (visuel / bande-annonce muette en haut).
    if (!LocalIsTv.current) {
        var soundOn by remember(movie.id) { mutableStateOf(false) }
        var trailerShown by remember(movie.id) { mutableStateOf(false) }
        val mp4 = allocineUrl
        val compact = rememberIsCompact()
        val tmdbRef = remember(movie.id) { TmdbRef.movie(movie.id) }
        val tmdbArt = rememberTmdbArt(tmdbRef)
        // Visuel paysage : celui du fournisseur, sinon TMDB ; jamais l'affiche portrait étirée en bandeau.
        val landscape = info?.backdrop?.takeIf { it.isNotBlank() } ?: tmdbArt.art?.backdrop
        val poster = movie.poster?.takeIf { it.isNotBlank() } ?: info?.cover ?: tmdbArt.art?.poster
        // Tablette : visuel moins haut quand il n'y a que l'affiche floutée (l'affiche nette est dans la colonne de gauche).
        val artHeight = if (compact) null
        else if (landscape != null || tmdbArt.loading) detailArtHeight() else detailArtHeight() * 0.6f
        val playLabel = if (resumable) "Reprendre à ${clock(progress!!.position)}" else "Lecture"
        val hasTrailer = mp4 != null || trailerId != null
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PhoneDetailArt(
                backdrop = landscape,
                poster = poster,
                loading = tmdbArt.loading,
                onBack = onBack,
                soundOn = if (trailerShown) soundOn else null,
                onToggleSound = { soundOn = !soundOn },
                showPoster = compact,
                height = artHeight,
            ) {
                if (trailerMode == TrailerMode.ALLOCINE && mp4 != null) {
                    // Découpée à la boîte du visuel (même hauteur que l'image, même fondu bas).
                    TrailerVideo(
                        mp4,
                        muted = false,
                        volumeOff = !soundOn,
                        modifier = Modifier.fillMaxSize(),
                        onFailed = { allocineFailed = true; trailerShown = false },
                        onStarted = { trailerShown = true },
                        timeoutMs = TRAILER_TIMEOUT_MS,
                        clipped = true,
                    )
                }
            }
            if (compact) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 29.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth()) { MovieMetaLine(movie, info, tmdbArt.art) }
                    Spacer(Modifier.height(14.dp))
                    ActionButton(playLabel, { play(playMode) }, Modifier.fillMaxWidth(), primary = true, icon = FluxIcons.Play)
                    if (resumable) {
                        Spacer(Modifier.height(8.dp))
                        ProgressLine(progress!!.fraction, Modifier.clip(RoundedCornerShape(2.dp)))
                        Spacer(Modifier.height(8.dp))
                        ActionButton(
                            "Depuis le début", { play(StartMode.FROM_START) },
                            Modifier.fillMaxWidth(), icon = FluxIcons.Refresh,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hasTrailer) {
                            ActionButton(
                                "Bande-annonce", { trailerShown = false; openTrailer(mp4) },
                                Modifier.weight(1f), icon = FluxIcons.Movie,
                            )
                        }
                        MyListButton(Favorites.movieKey(movie.id), Modifier.weight(1f))
                    }
                    info?.plot?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(16.dp))
                        Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.88f), lineHeight = 21.sp)
                    }
                    info?.director?.let {
                        Spacer(Modifier.height(12.dp))
                        Text("Réalisation : $it", fontSize = 13.sp, color = Palette.Dim, lineHeight = 19.sp)
                    }
                    info?.cast?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("Avec : $it", fontSize = 13.sp, color = Palette.Dim, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                // Tablette : affiche + réalisation à gauche ; titre, infos, boutons, résumé, casting à droite (680 dp au plus).
                TouchDetailColumns(
                    poster = poster,
                    title = title,
                    leftExtra = {
                        info?.director?.let {
                            Text("Réalisation", fontSize = 12.sp, color = Palette.Faint)
                            Text(it, fontSize = 13.sp, color = Palette.Dim, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    },
                ) {
                    Text(
                        title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 34.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth()) { MovieMetaLine(movie, info, tmdbArt.art) }
                    Spacer(Modifier.height(16.dp))
                    TouchButtonRow {
                        ActionButton(playLabel, { play(playMode) }, TouchButtonWidth, primary = true, icon = FluxIcons.Play)
                        if (resumable) {
                            ActionButton("Depuis le début", { play(StartMode.FROM_START) }, TouchButtonWidth, icon = FluxIcons.Refresh)
                        }
                        if (hasTrailer) {
                            ActionButton(
                                "Bande-annonce", { trailerShown = false; openTrailer(mp4) },
                                TouchButtonWidth, icon = FluxIcons.Movie,
                            )
                        }
                        MyListButton(Favorites.movieKey(movie.id), TouchButtonWidth)
                    }
                    if (resumable) {
                        Spacer(Modifier.height(10.dp))
                        ProgressLine(progress!!.fraction, Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(2.dp)))
                    }
                    info?.plot?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(16.dp))
                        Text(it, fontSize = 15.sp, color = Color.White.copy(alpha = 0.88f), lineHeight = 23.sp)
                    }
                    info?.cast?.let {
                        Spacer(Modifier.height(10.dp))
                        Text("Avec : $it", fontSize = 13.sp, color = Palette.Dim, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (saga != null) {
                Spacer(Modifier.height(8.dp))
                MovieRow("Dans la même saga  ·  ${displayTitle(saga.first)}", saga.second, movie, onOpenMovie)
            }
            if (similar.size >= 3) {
                Spacer(Modifier.height(8.dp))
                MovieRow("Titres similaires", similar, null, onOpenMovie)
            }
            // La dernière rangée dégage la barre de navigation du système.
            Spacer(Modifier.navigationBarsPadding().height(32.dp))
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        // ------------------------------------------------ Visuel (image, puis vidéo) + voiles
        Box(Modifier.fillMaxSize()) {
            // Zone du visuel : taille définie (70 % × toute la hauteur), dessinée SOUS les voiles.
            Box(
                Modifier.fillMaxWidth(ART_FRACTION).fillMaxHeight().align(Alignment.TopEnd)
                    .clipToBounds().zIndex(0f)
            ) {
                Crossfade(targetState = image, animationSpec = tween(450), label = "movieArt") { u ->
                    if (!u.isNullOrBlank()) {
                        AsyncImage(
                            model = u, contentDescription = null, contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                val mp4 = allocineUrl
                if (trailerMode == TrailerMode.ALLOCINE && mp4 != null) {
                    TrailerVideo(
                        mp4,
                        muted = false,
                        modifier = Modifier.fillMaxSize(),
                        onFailed = {
                            trailerLog("AlloCiné : lecture impossible ($mp4)")
                            allocineFailed = true
                        },
                        timeoutMs = TRAILER_TIMEOUT_MS,
                        edgeFade = true,
                    )
                }
                if (trailerMode == TrailerMode.YOUTUBE && trailerId != null) {
                    TrailerBackground(
                        trailerId,
                        Modifier.fillMaxSize(),
                        onFailed = {
                            trailerLog("YouTube : ${movie.name} → intégration refusée ou délai dépassé")
                            trailerFailed = true
                        },
                    )
                }
            }
            // Voiles au-dessus du visuel (zIndex) : opaque sous la colonne de texte, puis
            // fondu rapide pour laisser la vidéo bien visible.
            Box(
                Modifier.fillMaxSize().zIndex(1f).background(
                    Brush.horizontalGradient(
                        0f to Palette.Bg, 0.30f to Palette.Bg, 0.38f to Palette.Bg.copy(alpha = 0.75f),
                        0.50f to Palette.Bg.copy(alpha = 0.25f), 0.60f to Color.Transparent,
                    )
                )
            )
            Box(
                Modifier.fillMaxSize().zIndex(1f).background(
                    if (!hasBelow) Brush.verticalGradient(
                        0f to Color.Transparent, 0.75f to Color.Transparent, 1f to Palette.Bg.copy(alpha = 0.55f),
                    ) else Brush.verticalGradient(
                        0f to Color.Transparent, 0.52f to Color.Transparent, 0.72f to Palette.Bg.copy(alpha = 0.75f),
                        1f to Palette.Bg.copy(alpha = 0.92f),
                    )
                )
            )
        }

        // ------------------------------------------------ Informations
        // Le texte est limité en largeur ; la rangée de boutons, elle, ne l'est pas
        // (sinon « Bande-annonce » est tronqué quand trois boutons sont affichés).
        val textMax = Modifier.widthIn(max = TEXT_MAX_WIDTH)
        // Avec une saga : la page défile verticalement. Le bloc d'infos occupe tout l'écran moins un liseré
        // (le titre de la rangée « Dans la même saga » dépasse en bas) ; ▼ descend sur la rangée, ▲ remonte en haut.
        val pageScroll = rememberScrollState()
        val pageScope = rememberCoroutineScope()
        BoxWithConstraints(Modifier.fillMaxSize().zIndex(2f)) {
        // Sans rangée dessous (ni saga ni titres similaires) : tout l'écran sert au bloc d'informations.
        val heroHeight = if (!hasBelow) maxHeight else maxHeight - 56.dp
        Column(Modifier.fillMaxSize().then(if (hasBelow) Modifier.verticalScroll(pageScroll) else Modifier)) {
        Box(Modifier.fillMaxWidth().height(heroHeight)) {
        Column(
            Modifier.align(Alignment.CenterStart)
                .padding(start = 40.dp, end = 40.dp, top = 30.dp, bottom = if (!hasBelow) 30.dp else 12.dp)
                .onFocusChanged {
                    if (it.hasFocus && hasBelow && pageScroll.value != 0) pageScope.launch { pageScroll.animateScrollTo(0) }
                }
        ) {
            Text(
                title, modifier = textMax, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 36.sp,
            )
            Spacer(Modifier.height(8.dp))
            Box(textMax) { MovieMetaLine(movie, info) }
            info?.plot?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it, modifier = textMax, fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f),
                    maxLines = if (!hasBelow) 7 else 3,
                    overflow = TextOverflow.Ellipsis, lineHeight = 19.sp,
                )
            }
            val credits = listOfNotNull(
                info?.director?.let { "Réalisation : $it" },
                info?.cast?.let { "Avec : $it" },
            )
            if (credits.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                credits.forEach {
                    Text(it, modifier = textMax, fontSize = 12.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(
                    if (resumable) "Reprendre à ${clock(progress!!.position)}" else "Lecture",
                    // « Reprendre à … » : reprise directe, sans seconde invite dans le lecteur.
                    { play(playMode) },
                    Modifier.focusRequester(primaryFocus),
                    primary = true, icon = FluxIcons.Play,
                )
                if (resumable) {
                    ActionButton(
                        "Depuis le début",
                        // Depuis le début, sans invite de reprise dans le lecteur.
                        { play(StartMode.FROM_START) },
                        icon = FluxIcons.Refresh,
                    )
                }
                val mp4 = allocineUrl
                if (mp4 != null || trailerId != null) {
                    ActionButton("Bande-annonce", { openTrailer(mp4) }, icon = FluxIcons.Movie)
                }
                MyListButton(Favorites.movieKey(movie.id))
            }
            if (resumable) {
                Spacer(Modifier.height(14.dp))
                ProgressLine(progress!!.fraction, Modifier.widthIn(max = 260.dp))
            }
        }
        }
        if (saga != null) MovieRow("Dans la même saga  ·  ${displayTitle(saga.first)}", saga.second, movie, onOpenMovie)
        if (similar.size >= 3) MovieRow("Titres similaires", similar, null, onOpenMovie)
        }
        }
    }
}

/**
 * Rangée de films sous la fiche (saga : dans l'ordre, le film affiché signalé ; titres similaires).
 * Titres nettoyés (année dessous) ; le défilement garde une marge à gauche : le titre sélectionné n'est jamais coupé.
 */
@Composable
private fun MovieRow(title: String, films: List<Movie>, current: Movie?, onOpenMovie: (Movie) -> Unit) {
    // Tablette : vignettes plus grandes (titre sur 2 lignes) ; TV et téléphone inchangés.
    val cardWidth = if (!LocalIsTv.current && !rememberIsCompact()) 140.dp else 104.dp
    Column(Modifier.padding(bottom = 12.dp)) {
        SectionTitle(title, Modifier.padding(start = screenPadStart(), bottom = 2.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPaddedScrollSpec(screenPadStart())) {
            LazyRow(
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(start = screenPadStart(), end = screenPadEnd(), top = 8.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(films.size, key = { films[it].id }) { i ->
                    val m = films[i]
                    val isCurrent = m.id == current?.id
                    PosterCard(
                        displayTitle(m.name), m.poster, m.quality,
                        onClick = { if (!isCurrent) onOpenMovie(m) },
                        width = cardWidth,
                        subtitle = if (isCurrent) "Ce film" else (DisplayTitle.year(m.name) ?: Sagas.yearFromName(m.name))?.toString(),
                    )
                }
            }
        }
    }
}

/** Bouton « Ma liste » (fiches film et série) : ajoute ou retire l'élément des favoris. Libellé court. */
@Composable
internal fun MyListButton(key: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Favorites.load(ctx) }
    val items by Favorites.items.collectAsState()
    val inList = key in items
    ActionButton(
        "Ma liste",
        { Favorites.toggle(ctx, key) },
        modifier,
        icon = if (inList) FluxIcons.Check else FluxIcons.Plus,
    )
}

@Composable
private fun MovieMetaLine(movie: Movie, info: MovieInfo?, tmdb: com.fluxfr.tv.data.TmdbArt? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Même présentation que les bandeaux (HeroMeta) : « 2023 • Thriller, Drame • 1 h 42 • ★ 7,6 ».
        // [tmdb] (tactile) : complète l'année, les genres et la note quand le fournisseur n'en donne pas.
        val year = remember(movie.id, info, tmdb) {
            info?.year?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
                ?: VodMeta.get(VodMeta.movieKey(movie.id))?.year
                ?: DisplayTitle.year(movie.name)
                ?: tmdb?.year
        }
        val genres = remember(movie.id, info, tmdb) {
            val provided = info?.genre?.split(',', '/', '|')?.map { it.trim() }?.filter { it.isNotEmpty() }
            HomeRows.heroGenres(provided ?: VodMeta.get(VodMeta.movieKey(movie.id))?.genres, movie.category)
                .ifEmpty { tmdb?.genres.orEmpty().take(2) }
        }
        val rating = listOf(info?.rating, movie.rating, tmdb?.rating).firstOrNull { HomeRows.formatRating(it) != null }
        HeroMeta(
            year = year, genres = genres, rating = rating,
            trailing = info?.durationSecs?.takeIf { it > 0 }?.let { formatDuration(it * 1000) },
            quality = movie.quality, modifier = Modifier.weight(1f, fill = false),
        )
        // HDR / Dolby Vision / Atmos / 5.1 : analyse du fournisseur, complétée par le nom du film.
        val av = remember(movie.id, info) {
            (info?.av ?: AvFormat.NONE).merge(AvFormat.fromName(movie.name, movie.category))
        }
        if (!av.isEmpty) {
            Spacer(Modifier.size(10.dp))
            AvBadges(av)
        }
    }
}

/** 42:10 ou 1:02:10. */
private fun clock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Ouvre la bande-annonce dans l'appli YouTube, sinon dans le navigateur. */
private fun openYoutube(ctx: Context, id: String) {
    val intents = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")),
    )
    for (i in intents) {
        try {
            ctx.startActivity(i)
            return
        } catch (e: ActivityNotFoundException) {
        } catch (e: SecurityException) {
        }
    }
}

// ---------------------------------------------------------------- Bande-annonce en fond

/** Pont JavaScript → Kotlin (appelé sur un thread du WebView : on repasse sur le thread principal). */
private class TrailerBridge(private val view: View, private val onPlaying: () -> Unit, private val onError: () -> Unit) {
    @JavascriptInterface fun playing() { view.post(onPlaying) }
    @JavascriptInterface fun error(@Suppress("UNUSED_PARAMETER") code: String) { view.post(onError) }
}

/**
 * Lecture muette, sans contrôles et en boucle de la bande-annonce YouTube, dans un WebView
 * non focalisable. Invisible tant que la vidéo n'a pas démarré (l'image reste dessous) ;
 * [onFailed] si l'intégration est refusée (erreurs 101/150/153…) ou si rien ne démarre.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TrailerBackground(videoId: String, modifier: Modifier, onFailed: () -> Unit) {
    val ctx = LocalContext.current
    val failed by rememberUpdatedState(onFailed)
    var playing by remember(videoId) { mutableStateOf(false) }
    var errored by remember(videoId) { mutableStateOf(false) }

    val webView = remember(videoId) {
        try {
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(AndroidColor.BLACK)
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setOnTouchListener { _, _ -> true }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                addJavascriptInterface(
                    TrailerBridge(this, onPlaying = { playing = true }, onError = { errored = true }),
                    "FluxBridge",
                )
                loadDataWithBaseURL(
                    "https://www.youtube-nocookie.com/", trailerHtml(videoId), "text/html", "utf-8", null,
                )
            }
        } catch (e: Throwable) {
            null // WebView absent ou cassé sur l'appareil : on garde l'image.
        }
    }

    LaunchedEffect(webView, errored) {
        if (webView == null || errored) { failed(); return@LaunchedEffect }
        delay(TRAILER_TIMEOUT_MS)
        if (!playing) failed()
    }

    if (webView == null) return
    DisposableEffect(webView) {
        onDispose {
            runCatching {
                // Coupe le son tout de suite, avant la destruction.
                webView.evaluateJavascript("try{player&&player.stopVideo();}catch(x){}", null)
                webView.onPause()
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.removeJavascriptInterface("FluxBridge")
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
    }

    val alpha by animateFloatAsState(if (playing && !errored) 1f else 0f, tween(900), label = "trailer")
    // Boîte de taille définie (fillMaxSize du parent) ; le WebView la remplit (MATCH_PARENT).
    Box(modifier.focusProperties { canFocus = false }.alpha(alpha)) {
        AndroidView(
            factory = {
                webView.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
        )
        TrailerEdgeFade()
    }
}

private fun trailerHtml(id: String): String = """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
  /* La vidéo 16:9 couvre toute la zone (recadrée, centrée), comme une image en « crop ». */
  #p { position: absolute; top: 50%; left: 50%;
       width: max(100vw, 177.78vh); height: max(100vh, 56.25vw);
       transform: translate(-50%, -50%); pointer-events: none; border: 0; }
</style>
</head><body>
<div id="p"></div>
<script>
  var player = null, started = false;
  /* Taille en pixels recalculée : le WebView peut être créé avant d'avoir sa taille définitive. */
  function fit() {
    var el = document.getElementById('p');
    var w = window.innerWidth || document.documentElement.clientWidth;
    var h = window.innerHeight || document.documentElement.clientHeight;
    if (!el || !w || !h) return;
    el.style.width = Math.ceil(Math.max(w, h * 16 / 9)) + 'px';
    el.style.height = Math.ceil(Math.max(h, w * 9 / 16)) + 'px';
  }
  window.addEventListener('resize', fit);
  fit();
  function noCaptions(p) {
    try { p.unloadModule('captions'); } catch (x) {}
    try { p.unloadModule('cc'); } catch (x) {}
  }
  function sound(p) { try { p.unMute(); p.setVolume(100); } catch (x) {} }
  var tag = document.createElement('script');
  tag.src = 'https://www.youtube.com/iframe_api';
  tag.onerror = function () { FluxBridge.error('load'); };
  document.head.appendChild(tag);
  function onYouTubeIframeAPIReady() {
    player = new YT.Player('p', {
      host: 'https://www.youtube-nocookie.com',
      width: '100%', height: '100%',
      videoId: '$id',
      playerVars: { autoplay: 1, mute: 0, controls: 0, loop: 1, playlist: '$id', modestbranding: 1,
                    rel: 0, playsinline: 1, iv_load_policy: 3, disablekb: 1, fs: 0, cc_load_policy: 0,
                    hl: 'fr', showinfo: 0 },
      events: {
        onReady: function (e) {
          fit(); noCaptions(e.target); sound(e.target); e.target.playVideo();
          /* Lecture avec son bloquée ? On repart en muet, le son est remis dès PLAYING. */
          setTimeout(function () { if (!started) { e.target.mute(); e.target.playVideo(); } }, 2000);
        },
        onStateChange: function (e) {
          if (e.data === 1) {
            fit(); noCaptions(e.target); sound(e.target);
            if (!started) { started = true; FluxBridge.playing(); }
          }
          if (e.data === 0) { e.target.seekTo(0); e.target.playVideo(); }
        },
        onApiChange: function (e) { noCaptions(e.target); },
        onError: function (e) { FluxBridge.error(String(e.data)); }
      }
    });
    fit();
  }
</script>
</body></html>
""".trimIndent()
