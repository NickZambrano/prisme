package com.fluxfr.tv.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Text
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.withoutHidden
import com.fluxfr.tv.data.Profiles
import com.fluxfr.tv.data.kidsOnly
import androidx.compose.foundation.shape.CircleShape
import com.fluxfr.tv.data.CatalogRepository
import com.fluxfr.tv.data.Movie
import com.fluxfr.tv.data.Series
import com.fluxfr.tv.data.Settings
import com.fluxfr.tv.AppEvents
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch

/**
 * Sections du menu, dans l'ordre du rail TV (Réglages tout en bas). « TNT & Guide » = TNT + Guide TV (onglets),
 * icône téléviseur ; « Toutes les chaînes » = toutes les catégories du fournisseur, icône grille.
 */
enum class Section(val label: String, val icon: ImageVector) {
    SEARCH("Recherche", FluxIcons.Search),
    HOME("Accueil", FluxIcons.Home),
    MOVIES("Films", FluxIcons.Movie),
    SERIES("Séries", FluxIcons.Series),
    TNT("TNT & Guide", FluxIcons.Live),
    LIVE("Toutes les chaînes", FluxIcons.Tnt),
    /** Vidéos de l'appareil, clés USB et partages réseau : seulement si l'option est active (Réglages → Lecture). */
    FILES("Fichiers", FilesIcons.Folder),
    SETTINGS("Réglages", FluxIcons.Settings),
}

private const val CATALOG_MAX_AGE_MS = 12 * 60 * 60 * 1000L
private const val PROFILE_ASK_GRACE_MS = 5 * 60 * 1000L

/** Estime le défilement vertical de l'Accueil (cumul des défilements consommés) ; [onFar] : au-delà de ~1500 px. */
private class HomeScrollTracker(private val onFar: (Boolean) -> Unit) : NestedScrollConnection {
    private var y = 0f
    private var far = false
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.y != 0f) {
            y = (y - consumed.y).coerceAtLeast(0f)
            val f = y > 1500f
            if (f != far) { far = f; onFar(f) }
        }
        return Offset.Zero
    }
    fun reset() { y = 0f; if (far) { far = false; onFar(false) } }
}

/** Mémoire du processus (survit à la fermeture de l'activité) : « Qui regarde ? » déjà répondu, dernier départ. */
private object ProfileAskMemory {
    @Volatile var answered = false
    @Volatile var leftAt = 0L
}

/** Traduit les messages techniques reconnaissables (réseau) en français ; les autres sont gardés tels quels. */
fun friendlyError(raw: String): String {
    val m = raw.lowercase()
    return when {
        "unknownhost" in m || "unable to resolve host" in m || "connectexception" in m ||
            "failed to connect" in m || "timeout" in m || "timed out" in m || "connection refused" in m ||
            "network is unreachable" in m ->
            "Impossible de joindre le serveur. Vérifie la connexion Internet."
        else -> raw
    }
}

private val RAIL_COLLAPSED = 72.dp
private val RAIL_EXPANDED = 240.dp

@Composable
fun FluxApp() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var configured by remember { mutableStateOf(settings.isConfigured) }
    var forceReload by remember { mutableStateOf(false) }
    remember {
        Profiles.load(ctx)
        val app = ctx.applicationContext
        // Réseau local : mises à jour entre TV et synchronisation des réglages (démarre une seule fois).
        com.fluxfr.tv.data.PrismeNode.start(app)
        true
    }
    // « Qui regarde ? » : une fois par lancement, s'il y a au moins 2 profils et si l'option est active.
    // Tactile : si le processus est resté en vie et que l'appli revient dans les 5 minutes, on ne redemande pas.
    val isTouch = !LocalIsTv.current
    var profileAsked by rememberSaveable {
        mutableStateOf(
            isTouch && ProfileAskMemory.answered &&
                System.currentTimeMillis() - ProfileAskMemory.leftAt < PROFILE_ASK_GRACE_MS,
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { ProfileAskMemory.leftAt = System.currentTimeMillis() }
    if (profileAsked) ProfileAskMemory.answered = true
    val profiles by Profiles.list.collectAsState()

    if (!configured) {
        SetupScreen(settings, onDone = { forceReload = true; configured = true })
        return
    }

    LaunchedEffect(Unit) { CatalogRepository.load(ctx, force = forceReload) }
    val state by CatalogRepository.state.collectAsState()
    val scope = rememberCoroutineScope()
    // Retour au premier plan (ON_START, hors démarrage) : rafraîchit le catalogue s'il a plus de 12 h.
    // Rien n'est relancé si le catalogue est récent, pour ne pas recréer l'objet Catalog (et l'état des écrans).
    var started by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (!started) {
            started = true
        } else {
            val s = CatalogRepository.state.value
            val stale = s is CatalogRepository.State.Ready && !s.refreshing &&
                System.currentTimeMillis() - s.catalog.fetchedAt > CATALOG_MAX_AGE_MS
            if (stale) scope.launch { CatalogRepository.load(ctx) }
        }
    }
    val logout = {
        settings.logout()
        CatalogRepository.clear(ctx)
        configured = false
    }

    // Le catalogue se charge pendant qu'on choisit son profil.
    if (!profileAsked && profiles.size >= 2 && Profiles.askAtStart(ctx)) {
        WhoIsWatchingScreen(onDone = { profileAsked = true })
        return
    }

    when (val s = state) {
        is CatalogRepository.State.Ready -> MainScreen(
            catalog = s.catalog,
            refreshing = s.refreshing,
            refreshProgress = s.progress,
            incomplete = s.incomplete,
            settings = settings,
            onReload = { scope.launch { CatalogRepository.load(ctx, force = true) } },
            onLogout = logout,
        )
        is CatalogRepository.State.Failed -> CenterMessage(
            title = "Impossible de charger le catalogue",
            detail = friendlyError(s.message),
            actions = {
                ActionButton("Réessayer", { scope.launch { CatalogRepository.load(ctx, force = true) } }, primary = true, icon = FluxIcons.Refresh)
                // Profil Enfants : pas de déconnexion du compte IPTV (échappatoire au contrôle parental).
                if (!Profiles.active.collectAsState().value.kids) ActionButton("Changer de compte", logout)
            },
        )
        // Loading n'est publié que sans catalogue en cache (vrai premier chargement) ; Idle = lecture du cache
        // au démarrage normal : juste le logo (ni barre « 0 % » ni phrase « premier chargement »).
        is CatalogRepository.State.Loading -> LoadingScreen(s.message, s.progress, s.detail)
        CatalogRepository.State.Idle -> SplashLogo()
    }
}

/** Démarrage normal (catalogue en cache en cours de lecture) : seulement le logo. */
@Composable
fun SplashLogo() {
    Box(Modifier.fillMaxSize().background(Palette.BgBrush), contentAlignment = Alignment.Center) {
        BrandLogo()
    }
}

/** Vrai premier chargement (aucun catalogue en cache) : progression et explication. */
@Composable
fun LoadingScreen(message: String, progress: Float, detail: String?) {
    // Pas d'écran de veille pendant le chargement.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400), label = "progress")
    Box(Modifier.fillMaxSize().background(Palette.BgBrush), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = if (LocalIsTv.current) Modifier.width(420.dp) else Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 28.dp)) {
            BrandLogo()
            Spacer(Modifier.height(36.dp))
            Text(message, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.10f))
            ) {
                Box(
                    Modifier.fillMaxWidth(animated).height(6.dp).clip(RoundedCornerShape(50))
                        .background(Palette.AccentBrush)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(detail ?: "", fontSize = 12.sp, color = Palette.Dim, modifier = Modifier.weight(1f))
                Text("${(animated * 100).toInt()} %", fontSize = 12.sp, color = Palette.Dim)
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "Le premier chargement peut prendre un peu de temps.\nEnsuite, l'appli démarre instantanément.",
                fontSize = 12.sp, color = Palette.Faint, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun CenterMessage(title: String, detail: String, actions: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize().background(Palette.BgBrush), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = if (LocalIsTv.current) Modifier.width(560.dp) else Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            BrandLogo()
            Spacer(Modifier.height(28.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(detail, fontSize = 13.sp, color = Palette.Dim, textAlign = TextAlign.Center, maxLines = 4)
            if (actions != null) {
                Spacer(Modifier.height(26.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { actions() }
            }
        }
    }
}

@Composable
fun MainScreen(
    catalog: Catalog,
    refreshing: Boolean,
    refreshProgress: Float,
    settings: Settings,
    onReload: () -> Unit,
    onLogout: () -> Unit,
    /** Catalogue chargé en partie (CatalogRepository.State.Ready.incomplete) : petit avis non bloquant. */
    incomplete: Boolean = false,
) {
    val ctxCat = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { com.fluxfr.tv.data.HiddenCategories.load(ctxCat) }
    val hiddenCats by com.fluxfr.tv.data.HiddenCategories.hidden.collectAsState()
    remember { Profiles.load(ctxCat) }
    val profile by Profiles.current.collectAsState()
    val activeProfile by Profiles.active.collectAsState()
    val kids = activeProfile.kids
    // Google Drive (profil lié uniquement) : au démarrage, au changement de profil, au retour au premier plan,
    // puis toutes les 10 min tant que l'appli est au premier plan. Synchro en arrière-plan (Dispatchers.IO).
    var foreground by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { foreground = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { foreground = false }
    LaunchedEffect(profile, foreground) {
        if (!foreground) return@LaunchedEffect
        var first = true
        while (true) {
            com.fluxfr.tv.data.DriveSync.requestSync(ctxCat, profile, minIntervalMs = if (first) 30_000L else 0L)
            first = false
            delay(10 * 60 * 1000L)
        }
    }
    // Catégories masquées appliquées à l'affichage (la TNT garde le catalogue complet).
    // Profil Enfants : seulement la jeunesse, et sans catalogue complet derrière (unfiltered = lui-même),
    // pour que la TNT de l'accueil ne retrouve que des chaînes jeunesse.
    val visibleCatalog = remember(catalog, hiddenCats, kids) {
        catalog.withoutHidden(hiddenCats).let {
            if (!kids) it
            else it.kidsOnly().let { k ->
                Catalog(
                    channels = k.channels, movies = k.movies, series = k.series,
                    channelCategories = k.channelCategories, movieCategories = k.movieCategories,
                    seriesCategories = k.seriesCategories, fetchedAt = k.fetchedAt, full = null,
                )
            }
        }
    }
    // Onglet « Fichiers » : seulement si l'option est active (Réglages → Lecture), jamais en profil Enfants.
    remember { com.fluxfr.tv.data.files.FilesStore.load(ctxCat) }
    val filesOn by com.fluxfr.tv.data.files.FilesStore.enabled.collectAsState()
    val sections = remember(kids, filesOn) {
        Section.entries.filter { s ->
            when (s) {
                Section.TNT, Section.SETTINGS -> !kids
                Section.FILES -> filesOn && !kids
                else -> true
            }
        }
    }
    var chosenSection by rememberSaveable { mutableStateOf(Section.HOME) }
    // Section affichée : une section masquée (profil Enfants) retombe sur l'Accueil.
    val section = if (chosenSection in sections) chosenSection else Section.HOME
    var openSeries by remember { mutableStateOf<Series?>(null) }
    var openMovie by remember { mutableStateOf<Movie?>(null) }
    // Une fiche (série ou film) est ouverte par-dessus la section.
    val detailOpen = openSeries != null || openMovie != null
    // TV : vignette qui a ouvert la fiche (notée à l'ouverture), qui retrouve le focus à la fermeture.
    val focusMemory = remember { FocusMemory() }
    var detailReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val showSeries: (Series) -> Unit = {
        if (!detailOpen) detailReturnFocus = focusMemory.last
        openMovie = null; openSeries = it
    }
    val showMovie: (Movie) -> Unit = {
        if (!detailOpen) detailReturnFocus = focusMemory.last
        openSeries = null; openMovie = it
    }
    var railFocused by remember { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    val activity = LocalContext.current as? Activity

    val railRequesters = remember { Section.entries.associateWith { FocusRequester() } }
    val sectionFocus = remember { FocusRequester() }
    val detailFocus = remember { FocusRequester() }
    // Incrémenté quand la fiche série se ferme : rend le focus à la vignette qui l'a ouverte (focusRestorer).
    var restoreTick by remember { mutableIntStateOf(0) }
    var exitArmed by remember { mutableStateOf(false) }

    fun closeDetail() {
        openSeries = null
        openMovie = null
        restoreTick++
    }

    val isTv = LocalIsTv.current
    // Téléphone : menu « Plus » (Guide, TNT, Réglages, profil) ouvert par la barre du bas.
    var moreOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = detailOpen) { closeDetail() }
    BackHandler(enabled = !detailOpen && section != Section.HOME) { chosenSection = Section.HOME }
    // Téléphone : Accueil défilé loin vers le bas, Retour remonte d'abord en haut ; le suivant quitte l'appli.
    // Le défilement est estimé par un NestedScroll posé autour de l'Accueil (son état interne n'est pas exposé).
    var homeScrolledFar by remember { mutableStateOf(false) }
    val homeNested = remember { HomeScrollTracker { homeScrolledFar = it } }
    // Téléphone : Retour sur l'Accueil quitte l'appli (comportement système, pas de double appui).
    BackHandler(enabled = isTv && !detailOpen && section == Section.HOME) {
        when {
            !railFocused -> runCatching { railRequesters.getValue(Section.HOME).requestFocus() }
            exitArmed -> activity?.finish()
            else -> exitArmed = true
        }
    }
    LaunchedEffect(exitArmed) {
        if (exitArmed) { delay(2000); exitArmed = false }
    }
    LaunchedEffect(railFocused) { if (!railFocused) exitArmed = false }
    LaunchedEffect(restoreTick) {
        if (restoreTick == 0) return@LaunchedEffect
        val back = detailReturnFocus
        detailReturnFocus = null
        // La section ne reprend le focus qu'une fois la fiche retirée (focusProperties « enter ») : quelques images.
        if (back != null) repeat(4) {
            if (runCatching { back.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { }
        }
        runCatching { sectionFocus.requestFocus() }
    }

    // Rappels (guide / matchs) : minuteur de processus, toutes les 30 s.
    LaunchedEffect(Unit) { com.fluxfr.tv.data.Reminders.start(ctxCat) }
    // Changement de profil : relit ses rappels (hors thread principal).
    LaunchedEffect(profile) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.fluxfr.tv.data.Reminders.load(ctxCat) }
    }

    // Liens profonds (recherche Google TV / Assistant, fiches) : ouvre la fiche ou la recherche.
    val deepLink by com.fluxfr.tv.DeepLinks.pending.collectAsState()
    var searchSeed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(deepLink, visibleCatalog) {
        val t = deepLink ?: return@LaunchedEffect
        when (t) {
            is com.fluxfr.tv.DeepLinks.Target.OpenMovie -> visibleCatalog.movieById[t.id]?.let { showMovie(it) }
            is com.fluxfr.tv.DeepLinks.Target.OpenSeries -> visibleCatalog.seriesById[t.id]?.let { showSeries(it) }
            is com.fluxfr.tv.DeepLinks.Target.Search -> {
                openMovie = null; openSeries = null
                searchSeed = t.query
                chosenSection = Section.SEARCH
            }
        }
        com.fluxfr.tv.DeepLinks.consume()
    }

    // ---- Profils : fenêtre de choix (déclarée après les autres BackHandler : prioritaire).
    var profileDialog by remember { mutableStateOf(false) }
    var profileDialogShown by remember { mutableStateOf(false) }
    val profileFocus = remember { FocusRequester() }
    BackHandler(enabled = !isTv && moreOpen && !profileDialog) { moreOpen = false }
    BackHandler(enabled = profileDialog) { profileDialog = false }
    LaunchedEffect(profileDialog) {
        if (profileDialog) profileDialogShown = true
        else if (profileDialogShown) { delay(30); runCatching { profileFocus.requestFocus() } }
    }
    // Changement de profil : on ferme la fiche ouverte (elle peut ne pas être visible dans l'autre profil).
    var lastProfile by remember { mutableStateOf(profile) }
    LaunchedEffect(profile) {
        if (lastProfile != profile) {
            lastProfile = profile
            openSeries = null
            openMovie = null
            if (chosenSection !in sections) chosenSection = Section.HOME
            if (!profileDialog) { delay(30); runCatching { railRequesters.getValue(Section.HOME).requestFocus() } }
        }
    }

    // Ouverture de l'appli (lanceur, retour après une longue absence) : Accueil, fiche fermée ; l'accueil
    // remonte en haut et donne le focus au bouton de l'en-tête ([homeTopTick], traité par HomeScreen).
    var homeTopTick by remember { mutableIntStateOf(0) }
    // Tactile : Retour sur l'Accueil défilé loin du haut remonte d'abord (les fenêtres ouvertes gardent la priorité).
    BackHandler(enabled = !isTv && !detailOpen && !moreOpen && !profileDialog && section == Section.HOME && homeScrolledFar) {
        homeTopTick++
        homeNested.reset()
    }
    // Re-toucher la section active (barre / rail tactiles) : son contenu remonte en haut (voir [LocalScrollToTop]).
    var sectionTopTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        AppEvents.goHomeTop
            .onSubscription { if (AppEvents.consumePending()) emit(Unit) }
            .collect {
                AppEvents.consumePending()
                openSeries = null
                openMovie = null
                exitArmed = false
                chosenSection = Section.HOME
                homeTopTick++
                homeNested.reset()
            }
    }

    // ---- Téléphone / tablette : barre de navigation en bas (ou rail sur grand écran), sans focus.
    if (!isTv) {
        val sectionContent: @Composable () -> Unit = {
          androidx.compose.runtime.CompositionLocalProvider(LocalScrollToTop provides sectionTopTick) {
            holder.SaveableStateProvider(section.name) {
                when (section) {
                    Section.HOME -> Box(Modifier.fillMaxSize().nestedScroll(homeNested)) {
                        HomeScreen(
                            visibleCatalog, onOpenSeries = showSeries, onOpenMovie = showMovie, scrollTopTick = homeTopTick,
                            onOpenProfile = { profileDialog = true },
                            catalogIncomplete = incomplete,
                        )
                    }
                    Section.TNT -> TvScreen(catalog)
                    Section.LIVE -> LiveScreen(visibleCatalog)
                    Section.MOVIES -> MoviesScreen(visibleCatalog, onOpenMovie = showMovie)
                    Section.SERIES -> SeriesScreen(visibleCatalog, onOpenSeries = showSeries)
                    Section.SEARCH -> SearchScreen(visibleCatalog, onOpenSeries = showSeries, onOpenMovie = showMovie, initialQuery = searchSeed, onSeedConsumed = { searchSeed = null })
                    Section.FILES -> FilesScreen()
                    Section.SETTINGS -> SettingsScreen(settings, onReload, onLogout, catalog)
                }
            }
          }
        }
        val select: (Section) -> Unit = { s ->
            moreOpen = false
            if (section == s && !detailOpen) {
                if (s == Section.HOME) { homeTopTick++; homeNested.reset() } else sectionTopTick++
            }
            chosenSection = s
            openSeries = null
            openMovie = null
        }
        PhoneMainLayout(
            sections = sections,
            section = section,
            detailOpen = detailOpen,
            moreOpen = moreOpen,
            profileDialog = profileDialog,
            profile = activeProfile,
            refreshing = refreshing,
            refreshProgress = refreshProgress,
            onSelect = select,
            onMore = { moreOpen = !moreOpen },
            onCloseMore = { moreOpen = false },
            onProfile = { moreOpen = false; profileDialog = true },
            onCloseProfile = { profileDialog = false },
            catalog = catalog,
            countCatalog = visibleCatalog,
            content = sectionContent,
            detail = {
                val series = openSeries
                val movie = openMovie
                if (series != null) SeriesDetailScreen(series, settings, detailFocus, onBack = { closeDetail() })
                else if (movie != null) MovieDetailScreen(movie, settings, detailFocus, onBack = { closeDetail() })
            },
        )
        return
    }

    val railWidth by animateDpAsState(if (railFocused) RAIL_EXPANDED else RAIL_COLLAPSED, tween(220), label = "rail")
    // OK sur une entrée du menu : le focus passe au contenu de la section (une fois celle-ci composée).
    var focusContentTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusContentTick) {
        if (focusContentTick == 0) return@LaunchedEffect
        repeat(6) {
            delay(40)
            val target = if (detailOpen) detailFocus else sectionFocus
            if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    // ---- Focus perdu (vignette retirée pendant qu'elle avait le focus : rangée recalculée au retour du lecteur,
    // titre de rangée changé…). Personne n'a plus le focus : on le rend (Accueil : à sa vignette / sa rangée, sinon
    // en haut ; ailleurs : fiche ou section). Sans ça, le prochain appui partait du bouton de l'en-tête alors que
    // la liste restait défilée plus bas.
    var anyFocus by remember { mutableStateOf(false) }
    var focusLostTick by remember { mutableIntStateOf(0) }
    var homeFocusLostTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusLostTick) {
        if (focusLostTick == 0) return@LaunchedEffect
        // Laisse passer un transfert de focus en cours (fermeture d'une fiche, d'une fenêtre…).
        repeat(3) { androidx.compose.runtime.withFrameNanos { } }
        if (anyFocus || profileDialog) return@LaunchedEffect
        when {
            detailOpen -> runCatching { detailFocus.requestFocus() }
            section == Section.HOME -> homeFocusLostTick++
            else -> runCatching { sectionFocus.requestFocus() }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalFocusMemory provides focusMemory) {
    Box(
        Modifier.fillMaxSize().background(Palette.Bg)
            .onFocusChanged {
                val had = anyFocus
                anyFocus = it.hasFocus
                if (had && !it.hasFocus) focusLostTick++
            }
    ) {
        // ---- Contenu (laisse la place du rail replié)
        Box(Modifier.fillMaxSize().padding(start = RAIL_COLLAPSED)) {
            // La section reste composée sous la fiche série (on y retrouve sa position au retour),
            // mais elle ne peut pas prendre le focus tant que la fiche est ouverte.
            Box(
                Modifier.fillMaxSize()
                    .focusProperties {
                        enter = { if (detailOpen) FocusRequester.Cancel else FocusRequester.Default }
                    }
                    .focusGroup()
            ) {
                Box(Modifier.fillMaxSize().focusRequester(sectionFocus).focusRestorer()) {
                    holder.SaveableStateProvider(section.name) {
                        when (section) {
                            Section.HOME -> HomeScreen(
                                visibleCatalog, onOpenSeries = showSeries, onOpenMovie = showMovie, scrollTopTick = homeTopTick,
                                focusLostTick = homeFocusLostTick,
                                catalogIncomplete = incomplete,
                            )
                            Section.TNT -> TvScreen(
                                catalog,
                                onExitLeft = { runCatching { railRequesters.getValue(Section.TNT).requestFocus() } },
                            )
                            Section.LIVE -> LiveScreen(visibleCatalog)
                            Section.MOVIES -> MoviesScreen(visibleCatalog, onOpenMovie = showMovie)
                            Section.SERIES -> SeriesScreen(visibleCatalog, onOpenSeries = showSeries)
                            Section.SEARCH -> SearchScreen(visibleCatalog, onOpenSeries = showSeries, onOpenMovie = showMovie, initialQuery = searchSeed, onSeedConsumed = { searchSeed = null })
                            Section.FILES -> FilesScreen()
                            Section.SETTINGS -> SettingsScreen(settings, onReload, onLogout, catalog)
                        }
                    }
                }
            }

            val series = openSeries
            val movie = openMovie
            if (series != null || movie != null) {
                Box(
                    Modifier.fillMaxSize().background(Palette.Bg)
                        .focusProperties {
                            // On ne sort de la fiche que vers le menu (◀), sur l'entrée de la section courante.
                            exit = { dir ->
                                when (dir) {
                                    FocusDirection.Left -> railRequesters.getValue(section)
                                    FocusDirection.Enter, FocusDirection.Exit -> FocusRequester.Default
                                    else -> FocusRequester.Cancel
                                }
                            }
                        }
                        .focusGroup()
                ) {
                    if (series != null) SeriesDetailScreen(series, settings, detailFocus)
                    else if (movie != null) MovieDetailScreen(movie, settings, detailFocus)
                }
            }
        }

        // ---- Voile quand le menu est déplié (le contenu ne bouge pas : le menu passe par-dessus)
        AnimatedVisibility(railFocused, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to Palette.Bg, 0.22f to Palette.Bg.copy(alpha = 0.92f), 0.45f to Palette.Bg.copy(alpha = 0.55f),
                        0.75f to Color.Transparent,
                    )
                )
            )
        }

        // ---- Menu latéral (façon Netflix / Google TV) : rail fin d'icônes, qui se déplie par-dessus le contenu
        // avec les libellés quand il reçoit le focus. Logo puis profil en haut, sections centrées, Réglages en bas.
        val mainSections = sections.filter { it != Section.SETTINGS }
        val selectRail: (Section) -> Unit = { s ->
            if (section == s && detailOpen) closeDetail()
            else { chosenSection = s; openSeries = null; openMovie = null }
            // OK sur une entrée : retour au contenu.
            focusContentTick++
        }
        // Les entrées du menu ne sont pas notées dans [focusMemory] : elle garde la dernière vignette du contenu.
        androidx.compose.runtime.CompositionLocalProvider(LocalFocusMemory provides null) {
        Column(
            Modifier.width(railWidth).fillMaxHeight()
                .background(
                    if (railFocused) Brush.horizontalGradient(listOf(Palette.Rail, Palette.Rail.copy(alpha = 0.96f)))
                    else Brush.horizontalGradient(listOf(Palette.Rail, Palette.Rail.copy(alpha = 0.0f)))
                )
                .onFocusChanged { railFocused = it.hasFocus }
                // ▶ : retour à la vignette quittée (Retour / ◀ vers le menu passent par requestFocus, que le
                // focusRestorer de la section ne mémorise pas : il ramenait sur la première rangée).
                .onPreviewKeyEvent { e ->
                    if (detailOpen || e.type != KeyEventType.KeyDown || e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                    val last = focusMemory.last ?: return@onPreviewKeyEvent false
                    runCatching { last.requestFocus() }.getOrDefault(false)
                }
                .focusProperties {
                    // Entrer dans le menu = arriver sur la section courante, pas sur l'icône la plus proche.
                    enter = { railRequesters.getValue(section) }
                    exit = { dir ->
                        when {
                            detailOpen && dir == FocusDirection.Right -> detailFocus
                            dir == FocusDirection.Right -> sectionFocus
                            // Pas de bouclage ▲▼ ni de sortie vers le haut / le bas.
                            dir == FocusDirection.Up || dir == FocusDirection.Down -> FocusRequester.Cancel
                            else -> FocusRequester.Default
                        }
                    }
                }
                .focusGroup()
                .padding(start = 12.dp, end = 12.dp, top = 26.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.padding(start = 7.dp, bottom = 14.dp)) { BrandLogo(compact = !railFocused) }
            ProfileRailItem(
                profile = activeProfile,
                expanded = railFocused,
                onClick = { profileDialog = true },
                modifier = Modifier.focusRequester(profileFocus),
            )
            Spacer(Modifier.weight(1f))
            mainSections.forEach { s ->
                RailItem(
                    section = s,
                    selected = section == s && !detailOpen,
                    expanded = railFocused,
                    onClick = { selectRail(s) },
                    modifier = Modifier.focusRequester(railRequesters.getValue(s)),
                )
            }
            Spacer(Modifier.weight(1f))
            if (Section.SETTINGS in sections) {
                RailItem(
                    section = Section.SETTINGS,
                    selected = section == Section.SETTINGS && !detailOpen,
                    expanded = railFocused,
                    onClick = { selectRail(Section.SETTINGS) },
                    modifier = Modifier.focusRequester(railRequesters.getValue(Section.SETTINGS)),
                )
            }
            // Hauteur toujours réservée : les entrées ne « sautent » pas vers le haut quand le menu se déplie.
            Box(Modifier.fillMaxWidth().height(20.dp)) {
                if (railFocused) {
                    // Mêmes chiffres que les pages (catalogue affiché : catégories masquées retirées, profil Enfants).
                    val shown = visibleCatalog
                    Text(
                        if (incomplete) "Catalogue incomplet · nouvel essai bientôt"
                        else "${shown.channels.size} chaînes · ${shown.movies.size} films · ${shown.series.size} séries",
                        fontSize = 10.sp, color = Palette.Faint, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(start = 12.dp, top = 6.dp),
                    )
                }
            }
        }
        }

        // ---- « Appuie encore sur Retour pour quitter »
        AnimatedVisibility(
            exitArmed, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0xEE1D212D))
                    .padding(horizontal = 22.dp, vertical = 11.dp)
            ) {
                Text(
                    "Appuie encore sur Retour pour quitter",
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White,
                )
            }
        }

        // ---- Mise à jour du catalogue en arrière-plan : fine barre en haut
        if (refreshing) {
            val p by animateFloatAsState(refreshProgress.coerceIn(0f, 1f), tween(400), label = "refresh")
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.06f))) {
                Box(Modifier.fillMaxWidth(p).height(3.dp).background(Palette.AccentBrush))
            }
        }

        // ---- Nouveautés : une seule fois, au premier démarrage après une mise à jour (après le choix du profil).
        var whatsNew by remember { mutableStateOf<List<com.fluxfr.tv.data.Changelog.Entry>>(emptyList()) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1_500) // l'accueil d'abord
            whatsNew = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.fluxfr.tv.data.Changelog.pending(ctxCat) }.getOrDefault(emptyList())
            }
        }
        if (whatsNew.isNotEmpty() && !profileDialog) {
            WhatsNewDialog(whatsNew, "Nouveautés de Prisme ${com.fluxfr.tv.BuildConfig.VERSION_NAME}") {
                com.fluxfr.tv.data.Changelog.markSeen(ctxCat)
                whatsNew = emptyList()
                runCatching { sectionFocus.requestFocus() }
            }
        }

        // ---- Choix du profil (par-dessus tout, focus piégé)
        if (profileDialog) {
            ModalPanel(if (Profiles.list.value.size > 3) 860.dp else 560.dp) {
                ProfilePicker(
                    onChosen = { profileDialog = false },
                    subtitle = "Un profil Enfants n'affiche que les catégories jeunesse.",
                )
            }
        }

        // ---- Rappel « Ça commence » (par-dessus tout, en bas à droite)
        ReminderOverlay(
            catalog,
            onClosed = {
                runCatching { (if (detailOpen) detailFocus else sectionFocus).requestFocus() }
            },
        )
    }
    }
}

/** Entrée « Profil » en bas du menu : avatar du profil actif et son nom quand le menu est déplié. */
@Composable
private fun ProfileRailItem(profile: com.fluxfr.tv.data.Profile, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(50),
        focusedScale = 1.0f,
        containerColor = Color.Transparent,
        focusedContainerColor = Color.White,
        focusedContentColor = Color.Black,
        glow = false,
    ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(profile, 30.dp)
            if (expanded) {
                Spacer(Modifier.width(14.dp))
                Text(
                    profile.name,
                    fontSize = 16.sp, maxLines = 1, fontWeight = FontWeight.Medium,
                    color = if (focused) Color.Black else Palette.Dim,
                )
            }
        }
    }
}

@Composable
private fun RailItem(
    section: Section,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    // Pas de pavé gris : seule l'entrée sélectionnée (par le focus) est surlignée en blanc ; la section
    // courante a son icône en couleur d'accent et une petite barre d'accent à gauche.
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(50),
        focusedScale = 1.0f,
        containerColor = Color.Transparent,
        focusedContainerColor = Color.White,
        focusedContentColor = Color.Black,
        glow = false,
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            FluxIcon(
                section.icon, size = 22.dp,
                tint = when {
                    focused -> Color.Black
                    selected -> Palette.Accent
                    else -> Palette.Dim
                },
            )
            if (expanded) {
                Spacer(Modifier.width(16.dp))
                Text(
                    section.label, fontSize = 16.sp, maxLines = 1, fontWeight = FontWeight.Medium,
                    color = when {
                        focused -> Color.Black
                        selected -> Color.White
                        else -> Palette.Dim
                    },
                )
            }
        }
        if (selected && !focused) {
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 2.dp).size(width = 3.dp, height = 16.dp)
                    .clip(RoundedCornerShape(50)).background(Palette.Accent)
            )
        }
    }
}
