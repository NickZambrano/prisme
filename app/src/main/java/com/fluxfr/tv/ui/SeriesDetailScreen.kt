package com.fluxfr.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clipToBounds
import coil.compose.AsyncImage
import com.fluxfr.tv.data.CatalogRepository
import com.fluxfr.tv.data.CatalogTitles
import com.fluxfr.tv.data.DisplayTitle
import com.fluxfr.tv.data.MiniJson
import com.fluxfr.tv.data.Tmdb
import com.fluxfr.tv.data.TmdbParse
import com.fluxfr.tv.data.VodMeta
import com.fluxfr.tv.data.Episode
import com.fluxfr.tv.data.Favorites
import com.fluxfr.tv.data.Progress
import com.fluxfr.tv.data.Quality
import com.fluxfr.tv.data.ProgressStore
import com.fluxfr.tv.data.Series
import com.fluxfr.tv.data.Settings
import com.fluxfr.tv.data.XtreamClient
import com.fluxfr.tv.player.PlayerSession
import com.fluxfr.tv.player.Playables
import com.fluxfr.tv.player.StartMode
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private val EP_PREFIX = Regex("(?i)^.*?S\\d+\\s*E\\d+\\s*[-–:|]*\\s*")

@Composable
fun SeriesDetailScreen(
    series: Series,
    settings: Settings,
    /** Porté par l'élément principal (lecture, chargement ou « Réessayer ») : reçoit le focus. */
    primaryFocus: FocusRequester = remember { FocusRequester() },
    /** Téléphone : flèche de retour en haut de la fiche. */
    onBack: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val store = remember { ProgressStore.get(ctx) }
    var episodes by remember(series.id) { mutableStateOf(series.episodes) }
    var plot by remember(series.id) { mutableStateOf(series.plot) }
    var error by remember(series.id) { mutableStateOf<String?>(null) }
    var retry by remember(series.id) { mutableIntStateOf(0) }

    LaunchedEffect(series.id, retry) {
        if (episodes != null && retry == 0) return@LaunchedEffect
        // Une liste plus récente a peut-être été chargée entre-temps (rafraîchissement du catalogue).
        val fresh = (CatalogRepository.state.value as? CatalogRepository.State.Ready)
            ?.catalog?.seriesById?.get(series.id)?.episodes
        if (!fresh.isNullOrEmpty()) {
            episodes = fresh
            return@LaunchedEffect
        }
        if (series.id.startsWith("m3u:")) {
            // Playlist M3U : pas d'API Xtream à interroger.
            error = "Épisodes indisponibles"
            return@LaunchedEffect
        }
        try {
            val info = withContext(Dispatchers.IO) {
                XtreamClient(settings.server, settings.user, settings.pass).seriesInfo(series.id)
            }
            episodes = info.episodes
            if (plot.isNullOrBlank()) plot = info.plot
        } catch (e: Exception) {
            error = episodesError(e)
        }
    }
    // « Nouvel épisode » : ouvrir la fiche marque la série comme vue (puis son dernier épisode, une fois chargés).
    LaunchedEffect(series.id, episodes) {
        val eps = episodes
        withContext(Dispatchers.IO) {
            runCatching {
                val current = (CatalogRepository.state.value as? CatalogRepository.State.Ready)
                    ?.catalog?.unfiltered?.seriesById?.get(series.id) ?: series
                com.fluxfr.tv.data.NewEpisodes.markSeen(ctx, com.fluxfr.tv.data.Profiles.current.value, current, eps)
            }
        }
    }
    fun reload() {
        error = null
        episodes = null
        retry++
    }

    val version by ProgressStore.version.collectAsState()
    // SQLite hors du thread principal ; l'ancienne valeur reste affichée pendant le rechargement.
    val progressLoaded by produceState(Pair(series.id, emptyList<com.fluxfr.tv.data.Progress>()), version, series.id) {
        val id = series.id
        value = id to withContext(Dispatchers.IO) { store.forSeries(id) }
    }
    val progress = if (progressLoaded.first == series.id) progressLoaded.second else emptyList()
    val byEpisode = remember(progress) { progress.associateBy { it.itemId } }
    // Le fournisseur peut renvoyer des identifiants d'épisode en double : clés de listes uniques.
    val eps = remember(episodes) { episodes.orEmpty().distinctBy { it.id } }

    // Où reprendre : épisode entamé, sinon le suivant du dernier terminé, sinon le premier.
    val last = progress.firstOrNull()
    val lastIndex = last?.let { l -> eps.indexOfFirst { it.id == l.itemId } } ?: -1
    val (targetIndex, resumeLabel) = when {
        eps.isEmpty() -> -1 to ""
        last == null || lastIndex < 0 -> 0 to "Lire ${Playables.episodeCode(eps[0])}"
        !last.finished -> lastIndex to "Reprendre ${Playables.episodeCode(eps[lastIndex])}  ·  ${formatDuration(last.position)}"
        lastIndex + 1 < eps.size -> (lastIndex + 1) to "Épisode suivant ${Playables.episodeCode(eps[lastIndex + 1])}"
        else -> 0 to "Terminé ✓  ·  Revoir depuis le début"
    }
    // Bouton principal : épisode entamé → reprise directe (sans seconde invite dans le lecteur) ;
    // épisode neuf, suivant ou « Revoir depuis le début » → depuis le début.
    val targetMode = if (last != null && lastIndex >= 0 && !last.finished) StartMode.RESUME else StartMode.FROM_START

    val seasons = remember(eps) { eps.map { it.season }.distinct().sorted() }
    var season by remember(series.id) { mutableStateOf<Int?>(null) }
    val currentSeason = season ?: eps.getOrNull(targetIndex)?.season ?: seasons.firstOrNull()
    val seasonEpisodes = remember(eps, currentSeason) { eps.filter { it.season == currentSeason } }
    // Vignette, titre et résumé manquants chez le fournisseur : complétés par TMDB (saison affichée seulement).
    val isTv = LocalIsTv.current
    // Tactile : résumés TMDB en français même quand le fournisseur en a (souvent en anglais).
    val tmdbSeason = rememberTmdbSeason(series, currentSeason, seasonEpisodes, always = !isTv)
    val title = remember(series.name) { HomeRows.shownTitle(series.name) }

    fun play(index: Int, mode: StartMode = StartMode.ASK) {
        if (index in eps.indices) PlayerSession.start(ctx, Playables.episodes(series, eps), index, mode)
    }

    // L'élément principal change (chargement → lecture / réessayer) : on lui redonne le focus.
    val primaryState = when {
        error != null -> 2
        episodes == null -> 0
        eps.isEmpty() -> 2
        else -> 1
    }
    LaunchedEffect(primaryState) { runCatching { primaryFocus.requestFocus() } }

    // ---- Téléphone / tablette : fiche verticale, saisons en onglets, épisodes en liste (appui = lecture).
    if (!isTv) {
        val failed = error != null || (episodes != null && eps.isEmpty())
        val compact = rememberIsCompact()
        val tmdbRef = remember(series.id) { TmdbRef.series(series.id) }
        val tmdbArt = rememberTmdbArt(tmdbRef)
        // Visuel paysage TMDB ; sinon affiche floutée en fond (jamais l'affiche portrait étirée en bandeau).
        val landscape = tmdbArt.art?.backdrop
        val poster = series.poster?.takeIf { it.isNotBlank() } ?: tmdbArt.art?.poster
        val artHeight = if (compact) null
        else if (landscape != null || tmdbArt.loading) detailArtHeight() else detailArtHeight() * 0.6f
        val primaryButton: @Composable (Modifier) -> Unit = { m ->
            when {
                failed -> ActionButton("Réessayer", { reload() }, m, primary = true, icon = FluxIcons.Refresh)
                episodes == null -> ActionButton("Chargement des épisodes…", {}, m)
                else -> ActionButton(resumeLabel, { play(targetIndex, targetMode) }, m, primary = true, icon = FluxIcons.Play)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PhoneDetailArt(
                backdrop = landscape, poster = poster, loading = tmdbArt.loading, onBack = onBack,
                showPoster = compact, height = artHeight,
            )
            if (compact) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 29.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    MetaLine(series, seasons.size, eps.size, loaded = episodes != null, tmdb = tmdbArt.art)
                    Spacer(Modifier.height(14.dp))
                    if (failed) {
                        StatusText(error ?: "Aucun épisode disponible", if (error != null) Palette.Error else Palette.Dim)
                        Spacer(Modifier.height(10.dp))
                    }
                    primaryButton(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    MyListButton(Favorites.seriesKey(series.id), Modifier.fillMaxWidth())
                    plot?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(14.dp))
                        Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.88f), lineHeight = 21.sp)
                    }
                }
            } else {
                // Tablette : affiche à gauche ; titre, infos, boutons et résumé à droite (680 dp au plus).
                TouchDetailColumns(poster = poster, title = title) {
                    Text(
                        title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 34.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    MetaLine(series, seasons.size, eps.size, loaded = episodes != null, tmdb = tmdbArt.art)
                    Spacer(Modifier.height(16.dp))
                    if (failed) {
                        StatusText(error ?: "Aucun épisode disponible", if (error != null) Palette.Error else Palette.Dim)
                        Spacer(Modifier.height(10.dp))
                    }
                    TouchButtonRow {
                        primaryButton(TouchButtonWidth)
                        MyListButton(Favorites.seriesKey(series.id), TouchButtonWidth)
                    }
                    plot?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(16.dp))
                        Text(it, fontSize = 15.sp, color = Color.White.copy(alpha = 0.88f), lineHeight = 23.sp)
                    }
                }
            }
            if (eps.isNotEmpty()) {
                if (seasons.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = if (compact) 16.dp else 24.dp, vertical = 4.dp),
                    ) {
                        items(seasons, key = { it }) { s ->
                            Pill(if (s == 0) "Saison ?" else "Saison $s", selected = s == currentSeason, onClick = { season = s })
                        }
                    }
                } else {
                    Text(
                        "Épisodes", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = if (compact) 16.dp else 24.dp, top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (compact) {
                    seasonEpisodes.forEach { ep ->
                        key(ep.id) { PhoneEpisodeRow(ep, tmdbSeason[ep.number], byEpisode[ep.id]) { play(eps.indexOf(ep)) } }
                    }
                } else {
                    // Tablette : épisodes sur 2 colonnes, vignettes plus grandes.
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        seasonEpisodes.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                pair.forEach { ep ->
                                    key(ep.id) {
                                        Box(Modifier.weight(1f)) {
                                            PhoneEpisodeRow(ep, tmdbSeason[ep.number], byEpisode[ep.id], thumbWidth = 200.dp, plotLines = 3) {
                                                play(eps.indexOf(ep))
                                            }
                                        }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            // Le dernier épisode dégage la barre de navigation du système.
            Spacer(Modifier.navigationBarsPadding().height(32.dp))
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        SeriesBackdrop(series.poster)

        Column(Modifier.fillMaxSize().padding(top = 30.dp, bottom = 22.dp)) {
            // ------------------------------------------------ Informations
            Column(Modifier.padding(start = 40.dp, end = 56.dp).widthIn(max = 540.dp)) {
                Text(
                    title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 38.sp,
                )
                Spacer(Modifier.height(8.dp))
                MetaLine(series, seasons.size, eps.size, loaded = episodes != null)
                plot?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        it, fontSize = 13.sp, color = Palette.Dim, maxLines = 3,
                        overflow = TextOverflow.Ellipsis, lineHeight = 18.sp,
                    )
                }
                Spacer(Modifier.height(18.dp))
                val failed = error != null || (episodes != null && eps.isEmpty())
                if (failed) {
                    StatusText(error ?: "Aucun épisode disponible", if (error != null) Palette.Error else Palette.Dim)
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        failed -> ActionButton(
                            "Réessayer", { reload() }, Modifier.focusRequester(primaryFocus),
                            primary = true, icon = FluxIcons.Refresh,
                        )
                        // Élément focalisable pendant le chargement : le focus quitte tout de suite l'écran du dessous.
                        episodes == null -> ActionButton(
                            "Chargement des épisodes…", {}, Modifier.focusRequester(primaryFocus),
                        )
                        // Épisode entamé → reprise directe ; épisode neuf / « Revoir depuis le début » → depuis le début.
                        else -> ActionButton(
                            resumeLabel, { play(targetIndex, targetMode) }, Modifier.focusRequester(primaryFocus),
                            primary = true, icon = FluxIcons.Play,
                        )
                    }
                    MyListButton(Favorites.seriesKey(series.id))
                }
            }

            if (eps.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                // ------------------------------------------------ Saisons
                if (seasons.size > 1) {
                    LazyRow(
                        Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(start = 40.dp, end = 56.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        items(seasons, key = { it }) { s ->
                            Pill(
                                if (s == 0) "Saison ?" else "Saison $s",
                                selected = s == currentSeason,
                                onClick = { season = s },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text(
                        "Épisodes", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 40.dp, bottom = 2.dp),
                    )
                }

                // ------------------------------------------------ Épisodes
                key(currentSeason) {
                    val startAt = seasonEpisodes.indexOfFirst { eps.getOrNull(targetIndex)?.id == it.id }
                    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = startAt.coerceAtLeast(0))
                    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPaddedScrollSpec(40.dp)) {
                        LazyRow(
                            Modifier.fillMaxWidth().focusRestorer(),
                            state = rowState,
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(start = 40.dp, end = 56.dp, top = 10.dp, bottom = 8.dp),
                        ) {
                            items(seasonEpisodes, key = { it.id }) { ep ->
                                EpisodeCard(ep, tmdbSeason[ep.number], byEpisode[ep.id]) { play(eps.indexOf(ep)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Message d'erreur lisible pour le chargement des épisodes. */
private fun episodesError(e: Exception): String {
    var c: Throwable? = e
    while (c != null) {
        if (c is UnknownHostException || c is SocketTimeoutException || c is ConnectException ||
            c is InterruptedIOException
        ) return "Le serveur ne répond pas"
        c = c.cause
    }
    val m = e.message.orEmpty().lowercase()
    if ("timeout" in m || "timed out" in m || "unable to resolve host" in m) return "Le serveur ne répond pas"
    return "Impossible de charger les épisodes"
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(text, color = color, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

/** Fond de la fiche : visuel à droite (70 %), voiles opaques sous la colonne de texte (aucune arête visible). */
@Composable
private fun SeriesBackdrop(url: String?) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        if (!url.isNullOrBlank()) {
            // Zone de taille définie (comme la fiche film) : le bord gauche de l'image tombe sous le voile opaque.
            Box(Modifier.fillMaxWidth(0.70f).fillMaxHeight().align(Alignment.TopEnd).clipToBounds()) {
                val request = remember(url) {
                    coil.request.ImageRequest.Builder(ctx).data(url).size(880, 720).allowRgb565(true)
                        .precision(coil.size.Precision.INEXACT).build()
                }
                AsyncImage(
                    model = request, contentDescription = null, contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Palette.Bg, 0.30f to Palette.Bg, 0.38f to Palette.Bg.copy(alpha = 0.75f),
                    0.50f to Palette.Bg.copy(alpha = 0.25f), 0.60f to Color.Transparent,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.45f to Palette.Bg.copy(alpha = 0.55f), 0.75f to Palette.Bg, 1f to Palette.Bg)
            )
        )
    }
}

// ---------------------------------------------------------------- Épisodes : titre, résumé, vignette (fournisseur, sinon TMDB)

/** Ce que TMDB sait d'un épisode (saison affichée) : [overview] en français, [overviewEn] en repli. */
private class TmdbEp(val name: String?, val overview: String?, val still: String?, val overviewEn: String? = null)

private val GENERIC_EPISODE = Regex("""(?i)^\s*(?:épisode|episode|ep\.?|e|é)\s*0*(\d+)\s*$""")

/** Titre propre d'un épisode, ou null s'il est générique (« Episode 7 ») : le numéro seul suffit alors. */
private fun episodeName(ep: Episode, tm: TmdbEp?): String? {
    val own = EP_PREFIX.replace(ep.title, "").trim()
        .takeIf { it.isNotBlank() && !GENERIC_EPISODE.matches(it) }
    return own ?: tm?.name?.takeIf { it.isNotBlank() && !GENERIC_EPISODE.matches(it) }
}

private fun episodeImage(ep: Episode, tm: TmdbEp?): String? = ep.image?.takeIf { it.isNotBlank() } ?: tm?.still

/** Résumé : TMDB en français d'abord (le fournisseur l'a souvent en anglais), puis le fournisseur, puis TMDB en anglais. */
private fun episodePlot(ep: Episode, tm: TmdbEp?): String? =
    (tm?.overview?.takeIf { it.isNotBlank() } ?: ep.plot?.takeIf { it.isNotBlank() } ?: tm?.overviewEn?.takeIf { it.isNotBlank() })
        ?.replace('\n', ' ')?.trim()

/** « É7 · Titre » (ou « É7 » quand l'épisode n'a pas de vrai titre). */
private fun episodeLabel(ep: Episode, tm: TmdbEp?): String =
    "É${ep.number}" + (episodeName(ep, tm)?.let { " · $it" } ?: "")

/** Épisodes d'une réponse /tv/{id}/season/{n} : numéro → fiche. */
private fun parseSeason(json: String?): Map<Int, TmdbEp> {
    val root = json?.let { runCatching { MiniJson.parse(it) as? Map<*, *> }.getOrNull() } ?: return emptyMap()
    return (root["episodes"] as? List<*>).orEmpty().mapNotNull { e ->
        val m = e as? Map<*, *> ?: return@mapNotNull null
        val n = (m["episode_number"] as? Number)?.toInt() ?: return@mapNotNull null
        n to TmdbEp(
            name = (m["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            overview = (m["overview"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            still = (m["still_path"] as? String)?.takeIf { it.startsWith("/") }
                ?.let { "https://image.tmdb.org/t/p/w300$it" },
        )
    }.toMap()
}

/**
 * Saison TMDB en français ; résumé anglais en repli (1 requête de plus, cache 7 jours) seulement pour les épisodes
 * sans résumé français NI résumé du fournisseur ([noProviderPlot] : numéros de ces épisodes).
 */
private fun loadTmdbSeason(
    ctx: android.content.Context,
    series: Series,
    season: Int,
    noProviderPlot: Set<Int> = emptySet(),
): Map<Int, TmdbEp> {
    val fr = loadTmdbSeasonFr(ctx, series, season)
    if (fr.isEmpty()) return fr
    val missing = fr.filter { (n, e) -> e.overview == null && n in noProviderPlot }.keys
    if (missing.isEmpty()) return fr
    val id = Tmdb.knownId(ctx, VodMeta.seriesKey(series.id)) ?: return fr
    val en = parseSeason(
        runCatching {
            Tmdb.get(Tmdb.Session(ctx, refreshStale = true, maxRequests = 1), "/tv/$id/season/$season", "language" to "en-US")
        }.getOrNull()
    )
    return fr.mapValues { (n, e) ->
        if (n in missing) TmdbEp(e.name, e.overview, e.still, en[n]?.overview) else e
    }
}

private fun loadTmdbSeasonFr(ctx: android.content.Context, series: Series, season: Int): Map<Int, TmdbEp> {
    VodMeta.load(ctx)
    val key = VodMeta.seriesKey(series.id)
    val session = Tmdb.Session(ctx, refreshStale = true, maxRequests = 3)
    var id = VodMeta.get(key)?.tmdbId?.trim()?.takeIf { (it.toLongOrNull() ?: 0) > 0 } ?: Tmdb.resolvedId(ctx, key)
    if (id == null) {
        val title = CatalogTitles.cleanTitle(series.name).ifBlank { return emptyMap() }
        val year = VodMeta.get(key)?.year ?: CatalogTitles.yearIn(series.name)
        val params = buildList {
            add("query" to title)
            if (year != null) add("first_air_date_year" to year.toString())
        }
        id = TmdbParse.searchBest(Tmdb.get(session, "/search/tv", *params.toTypedArray()), Tmdb.TV, year)
            ?: return emptyMap()
        Tmdb.saveResolved(ctx, key, id)
    }
    // Français explicite (langue par défaut de [Tmdb.get]) : jamais l'anglais ici.
    val json = Tmdb.get(session, "/tv/$id/season/$season", "language" to "fr-FR") ?: return emptyMap()
    return parseSeason(json)
}

/**
 * Données TMDB de la saison affichée (numéro d'épisode → fiche) : toujours sur tactile ([always], résumés français),
 * sinon seulement si un épisode manque d'image, de résumé ou de vrai titre. Réseau et cache disque (7 jours)
 * hors du thread principal ; vide tant que rien n'est arrivé.
 */
@Composable
private fun rememberTmdbSeason(series: Series, season: Int?, episodes: List<Episode>, always: Boolean = false): Map<Int, TmdbEp> {
    val ctx = LocalContext.current.applicationContext
    val needed = season != null && season > 0 && episodes.isNotEmpty() && (always ||
        episodes.any { it.image.isNullOrBlank() || it.plot.isNullOrBlank() || episodeName(it, null) == null })
    val noPlot = remember(episodes) { episodes.filter { it.plot.isNullOrBlank() }.mapTo(HashSet()) { it.number } }
    val loaded by produceState(Pair<Int?, Map<Int, TmdbEp>>(null, emptyMap()), series.id, season, needed) {
        if (!needed || season == null) {
            value = null to emptyMap()
            return@produceState
        }
        val map = withContext(Dispatchers.IO) {
            runCatching { loadTmdbSeason(ctx, series, season, noPlot) }.getOrDefault(emptyMap())
        }
        value = season to map
    }
    return if (loaded.first == season) loaded.second else emptyMap()
}

@Composable
private fun MetaLine(series: Series, seasonCount: Int, episodeCount: Int, loaded: Boolean, tmdb: com.fluxfr.tv.data.TmdbArt? = null) {
    // Même présentation que les bandeaux (HeroMeta) : « 2023 • Drame, Thriller • 3 saisons  •  24 épisodes  •  ★ 7,9 ».
    // Jamais la catégorie brute du fournisseur (« LATEST SERIES ») ; TMDB ([tmdb], tactile) complète année, genres, note.
    val m = remember(series.id) { VodMeta.get(VodMeta.seriesKey(series.id)) }
    val counts = listOfNotNull(
        if (loaded && seasonCount > 0) (if (seasonCount > 1) "$seasonCount saisons" else "1 saison") else null,
        if (loaded && episodeCount > 0) (if (episodeCount > 1) "$episodeCount épisodes" else "1 épisode") else null,
    ).joinToString("  •  ").ifBlank { null }
    val genres = remember(series.id, m, tmdb) {
        val category = com.fluxfr.tv.data.searchKey(series.category)
        HomeRows.heroGenres(m?.genres, series.category)
            .filter { com.fluxfr.tv.data.searchKey(it) != category }
            .ifEmpty { tmdb?.genres.orEmpty().take(2) }
    }
    HeroMeta(
        year = m?.year ?: DisplayTitle.year(series.name) ?: tmdb?.year,
        genres = genres,
        rating = tmdb?.rating,
        trailing = counts, quality = series.quality,
    )
}

/** Téléphone / tablette : ligne d'épisode (vignette 16:9 à gauche, titre, état, résumé) ; appui = lecture. */
@Composable
private fun PhoneEpisodeRow(
    ep: Episode,
    tm: TmdbEp?,
    progress: Progress?,
    thumbWidth: androidx.compose.ui.unit.Dp = 132.dp,
    plotLines: Int = 2,
    onClick: () -> Unit,
) {
    val label = episodeLabel(ep, tm)
    val image = episodeImage(ep, tm)
    val plot = episodePlot(ep, tm)
    val watched = progress?.finished == true
    Row(
        Modifier.fillMaxWidth()
            // Un seul nœud d'accessibilité : « É3 · Titre », état.
            .semantics(mergeDescendants = true) {}
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(thumbWidth).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Palette.Card)) {
            if (image.isNullOrBlank()) NoStill(ep.number, focused = false)
            else AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.Center).size(32.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { FluxIcon(FluxIcons.Play, size = 18.dp) }
            if (watched) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).background(Palette.Success, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { FluxIcon(FluxIcons.Check, size = 12.dp, tint = Color.Black) }
            }
            if (progress != null && !progress.finished) ProgressLine(progress.fraction, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    watched -> "Vu"
                    progress != null -> "En cours · ${formatDuration(progress.position)}"
                    ep.durationSec > 0 -> formatDuration(ep.durationSec * 1000)
                    else -> "Saison ${ep.season}"
                },
                fontSize = 12.sp, color = if (watched) Palette.Success.copy(alpha = 0.85f) else Palette.Faint, maxLines = 1,
            )
            plot?.let {
                Text(it, fontSize = 12.sp, color = Palette.Dim, maxLines = plotLines, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            }
        }
    }
}

/** Épisode sans vignette : fond neutre et grand numéro (masqué au focus, où l'icône de lecture prend sa place). */
@Composable
private fun NoStill(number: Int, focused: Boolean) {
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF232842), Color(0xFF151821)))),
        contentAlignment = Alignment.Center,
    ) {
        if (!focused) Text("É$number", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.32f))
    }
}

/** Carte d'épisode 16:9 : vignette, durée, progression et marqueur « vu » ; « É7 · Titre » et un résumé dessous. */
@Composable
private fun EpisodeCard(ep: Episode, tm: TmdbEp?, progress: Progress?, onClick: () -> Unit) {
    val label = episodeLabel(ep, tm)
    val image = episodeImage(ep, tm)
    val plot = episodePlot(ep, tm)
    var focused by remember { mutableStateOf(false) }
    val watched = progress?.finished == true
    Column(Modifier.revealOnFocus().width(188.dp)) {
        FocusSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { focused = it.isFocused },
            shape = RoundedCornerShape(12.dp),
            focusedScale = 1.07f,
        ) {
            if (image.isNullOrBlank()) NoStill(ep.number, focused)
            else AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // Voile bas pour la lisibilité de la durée
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))
                )
            )
            if (ep.durationSec > 0) {
                Text(
                    formatDuration(ep.durationSec * 1000), fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 9.dp),
                )
            }
            if (watched) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp)
                        .background(Palette.Success, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { FluxIcon(FluxIcons.Check, size = 14.dp, tint = Color.Black) }
            }
            if (focused) {
                Box(
                    Modifier.align(Alignment.Center).size(38.dp).background(Color.White.copy(alpha = 0.9f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { FluxIcon(FluxIcons.Play, size = 22.dp, tint = Color.Black) }
            }
            if (progress != null && !progress.finished) {
                ProgressLine(progress.fraction, Modifier.align(Alignment.BottomCenter))
            }
        }
        Spacer(Modifier.height(8.dp))
        CardTitle(label, focused, unfocusedWeight = FontWeight.Medium)
        Text(
            when {
                watched -> "Vu"
                progress != null -> "En cours · ${formatDuration(progress.position)}"
                else -> plot ?: "Saison ${ep.season}"
            },
            fontSize = 11.sp, color = if (watched) Palette.Success.copy(alpha = 0.85f) else Palette.Faint,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
