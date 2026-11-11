package com.fluxfr.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.DisplayTitle
import com.fluxfr.tv.data.Genres
import com.fluxfr.tv.data.HiddenCategories
import com.fluxfr.tv.data.MetaEnricher
import com.fluxfr.tv.data.Movie
import com.fluxfr.tv.data.Progress
import com.fluxfr.tv.data.ProgressStore
import com.fluxfr.tv.data.Quality
import com.fluxfr.tv.data.Reco
import com.fluxfr.tv.data.RecoItem
import com.fluxfr.tv.data.RecoKind
import com.fluxfr.tv.data.RecoRows
import com.fluxfr.tv.data.Sagas
import com.fluxfr.tv.data.Series
import com.fluxfr.tv.data.Settings
import com.fluxfr.tv.data.Shelf
import com.fluxfr.tv.data.TmdbLists
import com.fluxfr.tv.data.VodMeta
import com.fluxfr.tv.data.XtreamClient
import com.fluxfr.tv.data.searchKey
import com.fluxfr.tv.player.Playables
import com.fluxfr.tv.player.PlayerSession
import com.fluxfr.tv.player.StartMode
import androidx.compose.foundation.layout.navigationBarsPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

// ================================================================ Films / Séries « à la Netflix »
//
// Page « À la une » : en-tête (titre, infos, résumé, boutons) qui suit la vignette sélectionnée, fond fixe,
// puis rangées horizontales (reprise, nouveautés, listes TMDB, genres, sagas, catégories du fournisseur).
// Chaque rangée finit par une carte « Tout voir » qui ouvre la grille (CategoryBrowser) sur ce genre /
// cette catégorie. Pastilles en haut : À la une | Genres | Catégories | A–Z.
//
// Fluidité (TV peu puissante) : rangées calculées hors du thread principal et mémorisées par catalogue ;
// l'élément sélectionné n'est JAMAIS lu dans le corps des écrans (seulement dans BrowseHero / BrowseBackdrop,
// via des lambdas, avec un court délai) : un déplacement du focus ne recompose pas les rangées.

internal const val MODE_FEATURED = "featured"
internal const val MODE_GENRES = "genres"
internal const val MODE_CATEGORIES = "categories"
internal const val MODE_AZ = "az"
/** Grille des sagas (ouverte depuis la rangée « Sagas » ; pas de pastille dédiée). */
internal const val MODE_SAGAS = "sagas"

private val MODE_PILLS = listOf(
    MODE_FEATURED to "À la une",
    MODE_GENRES to "Genres",
    MODE_CATEGORIES to "Catégories",
    MODE_AZ to "A–Z",
)

private const val ROW_MAX = 40
private const val MIN_GENRE_ROW = 6
private const val MAX_GENRE_ROWS = 12
private const val MIN_CATEGORY_ROW = 4
private const val MAX_ROWS = 25
private const val MAX_TMDB_ROWS = 4
private const val MAX_SAGAS = 30
private val UHD_WORDS = Regex("\\b(4k|uhd|2160p?)\\b")

/** Rangées TMDB d'une page (au plus [MAX_TMDB_ROWS], sans doublon). */
private fun <T> tmdbRows(shelves: List<Shelf<T>>, id: (T) -> String): List<BrowseRow<T>> =
    shelves.filter { it.items.isNotEmpty() }.take(MAX_TMDB_ROWS).mapIndexed { i, s ->
        BrowseRow("tmdb-$i-${s.title}", s.title, s.items.distinctBy(id).take(ROW_MAX))
    }

/** Cible de « Tout voir » : mode de la grille et catégorie / genre présélectionné. */
internal class SeeAll(val mode: String, val selection: String?)

internal class BrowseRow<T>(
    val id: String,
    val title: String,
    val items: List<T>,
    val seeAll: SeeAll? = null,
    /** Catégorie du fournisseur : appui long sur « Tout voir » pour la masquer. */
    val category: String? = null,
    val subtitle: ((T) -> String?)? = null,
    /** Progression propre à la rangée (sinon celle de l'écran). */
    val progress: ((T) -> Float?)? = null,
)

/** Rangées et index d'une section (calculés hors du thread principal). */
internal class BrowseData<T>(
    val genreIndex: GenreIndex<T>,
    /** Nouveautés, En 4K. */
    val head: List<BrowseRow<T>>,
    val genres: List<BrowseRow<T>>,
    /** Sagas (nom, films dans l'ordre de sortie). */
    val sagas: List<Pair<String, List<T>>>,
    val categories: List<BrowseRow<T>>,
    /** Tout le contenu par ordre alphabétique, et par initiale (« # » = chiffres / symboles). */
    val az: List<T>,
    val azLetters: List<String>,
    val azByLetter: Map<String, List<T>>,
)

internal fun <T> buildBrowseData(
    items: List<T>,
    categories: List<String>,
    byCat: Map<String, List<T>>,
    metaKey: (T) -> String,
    added: (T) -> Long,
    sortKey: (T) -> String,
    isUhd: ((T) -> Boolean)?,
    sagas: List<Pair<String, List<T>>>,
    /** Rangées ajoutées par l'écran (reprise, nouveaux épisodes, TMDB) : comptées dans le plafond. */
    reservedRows: Int,
): BrowseData<T> {
    val recentFirst = Comparator<T> { a, b -> added(b).compareTo(added(a)) }
    val genreIndex = buildGenreIndex(items, metaKey)

    val head = ArrayList<BrowseRow<T>>(2)
    head += BrowseRow("new", "Nouveautés", items.sortedWith(recentFirst).take(ROW_MAX), SeeAll(MODE_CATEGORIES, null))
    if (isUhd != null) {
        head += BrowseRow("uhd", "En 4K", items.filter(isUhd).sortedWith(recentFirst).take(ROW_MAX))
    }

    val genreNames = genreIndex.genres
        .filter { (genreIndex.byGenre[it]?.size ?: 0) >= MIN_GENRE_ROW }
        .take(MAX_GENRE_ROWS)
    val genres = genreNames.map { g ->
        BrowseRow("g-$g", g, genreIndex.byGenre.getValue(g).sortedWith(recentFirst).take(ROW_MAX), SeeAll(MODE_GENRES, g))
    }

    // Catégories du fournisseur qui ne font pas doublon avec une rangée déjà présente (genre, 4K).
    val covered = genreNames.toSet()
    val used = reservedRows + head.count { it.items.isNotEmpty() } + genres.size + (if (sagas.isEmpty()) 0 else 1)
    val budget = (MAX_ROWS - used).coerceAtLeast(0)
    val cats = ArrayList<BrowseRow<T>>()
    for (c in categories.distinct()) {
        if (cats.size >= budget) break
        val list = byCat[c].orEmpty()
        if (list.size < MIN_CATEGORY_ROW) continue
        if (isUhd != null && UHD_WORDS.containsMatchIn(searchKey(c))) continue
        val g = Genres.normalize(c)
        if (g.isNotEmpty() && g.all { it in covered }) continue
        cats += BrowseRow("c-$c", categoryLabel(c), list.sortedWith(recentFirst).take(ROW_MAX), SeeAll(MODE_CATEGORIES, c), category = c)
    }

    // A–Z
    val keyed = items.map { it to sortKey(it).trimStart { ch -> !ch.isLetterOrDigit() } }
    val az = keyed.sortedBy { it.second }
    val byLetter = LinkedHashMap<String, ArrayList<T>>()
    for ((t, k) in az) {
        val c = k.firstOrNull()
        val l = if (c != null && c in 'a'..'z') c.uppercaseChar().toString() else "#"
        byLetter.getOrPut(l) { ArrayList() }.add(t)
    }
    val letters = byLetter.keys.sortedWith(compareBy<String> { it != "#" }.thenBy { it })

    return BrowseData(genreIndex, head, genres, sagas.distinctBy { it.first }, cats, az.map { it.first }, letters, byLetter)
}

/** Rangées « Parce que tu as regardé » prévues dans le plafond de rangées. */
private const val MAX_BECAUSE_ROWS = 3

/**
 * Recommandations du profil actif ([Reco]) : dernières rangées connues tout de suite (mémoire / disque), puis
 * recalcul en arrière-plan, regroupé (2,5 s) quand l'historique ou Ma liste change. Jamais sur le thread principal.
 */
@Composable
internal fun rememberRecoRows(catalog: Catalog, kind: RecoKind?): RecoRows {
    val ctx = LocalContext.current
    val version by ProgressStore.version.collectAsState()
    val profile by com.fluxfr.tv.data.Profiles.current.collectAsState()
    val favs by com.fluxfr.tv.data.Favorites.items.collectAsState()
    val ignoreVersion by com.fluxfr.tv.data.RecoIgnore.version.collectAsState()
    val state = produceState(RecoRows.EMPTY, catalog, profile, version, favs, ignoreVersion) {
        val app = ctx.applicationContext
        val known = withContext(Dispatchers.Default) { runCatching { Reco.peek(app, catalog, kind) }.getOrNull() }
        value = known ?: RecoRows.EMPTY
        if (known != null) delay(2_500)
        withContext(Dispatchers.IO) { runCatching { Reco.rows(app, catalog, kind) }.getOrNull() }?.let { value = it }
    }
    return state.value
}

/** Dernières rangées calculées, par section : un retour sur la page est instantané. */
private object BrowseCache {
    class Entry(val catalog: Catalog, val version: Int, val data: BrowseData<*>)
    private val entries = HashMap<String, Entry>()
    fun get(kind: String): Entry? = synchronized(entries) { entries[kind] }
    fun put(kind: String, e: Entry) = synchronized(entries) { entries[kind] = e }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun <T> rememberBrowseData(kind: String, catalog: Catalog, build: () -> BrowseData<T>): BrowseData<T>? {
    val ctx = LocalContext.current
    val metaVersion by MetaEnricher.version.collectAsState()
    val latestBuild by rememberUpdatedState(build)
    val state = produceState(
        BrowseCache.get(kind)?.takeIf { it.catalog === catalog }?.data as BrowseData<T>?,
        catalog, metaVersion,
    ) {
        val prev = BrowseCache.get(kind)
        if (prev != null && prev.catalog === catalog) {
            value = prev.data as BrowseData<T>
            if (prev.version == metaVersion) return@produceState
            // Enrichissement des genres en cours : on regroupe les mises à jour.
            delay(1_500)
        }
        val d = withContext(Dispatchers.Default) {
            VodMeta.load(ctx.applicationContext)
            latestBuild()
        }
        BrowseCache.put(kind, BrowseCache.Entry(catalog, metaVersion, d))
        value = d
    }
    return state.value
}

// ---------------------------------------------------------------- En-tête

/** Ce que montre l'en-tête de la page « À la une ». */
internal class Hero(
    val key: String,
    val eyebrow: String,
    val title: String,
    val meta: String?,
    val plot: String?,
    val image: String?,
    val quality: Quality?,
    val primaryLabel: String,
    val primaryIcon: ImageVector,
    /** Résumé chargé à la demande (fiche du fournisseur), après un temps d'arrêt. */
    val loadPlot: (suspend () -> String?)? = null,
    val primary: () -> Unit,
    val info: () -> Unit,
    /** Ligne d'infos TV ([HeroMeta], partagée) ; [meta] en est la version texte (téléphone). */
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: String? = null,
    /** Texte libre placé en tête de la ligne d'infos (saga : années des films). */
    val leading: String? = null,
    /** Tactile : titre du catalogue dont on cherche le visuel paysage TMDB pour la grande carte. */
    val tmdb: TmdbRef? = null,
)

/** Valeur de [source] avec un court délai (seule la dernière vignette d'un défilement rapide s'affiche). */
@Composable
internal fun <T> rememberSettledValue(delayMs: Long, keyOf: (T) -> Any?, source: () -> T): T {
    val latest by rememberUpdatedState(source)
    return produceState(initialValue = source()) {
        snapshotFlow { latest() }.collectLatest { v ->
            if (keyOf(v) != keyOf(value)) { delay(delayMs); value = v }
        }
    }.value
}

@Composable
private fun BrowseHero(hero: () -> Hero?, primaryFocus: FocusRequester) {
    val h = rememberSettledValue(110, { it?.key }, hero)
    Box(Modifier.fillMaxWidth().height(204.dp).padding(start = 40.dp, top = 10.dp, end = 320.dp)) {
        if (h != null) {
            val loader = h.loadPlot
            val fetched by produceState<String?>(null, h.key) {
                if (loader != null) {
                    delay(450)
                    value = withContext(Dispatchers.IO) { runCatching { loader() }.getOrNull() }
                }
            }
            Column {
                Text(
                    h.eyebrow.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = Palette.Accent, letterSpacing = 1.5.sp, maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    h.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, lineHeight = 32.sp,
                )
                Spacer(Modifier.height(6.dp))
                HeroMeta(year = h.year, genres = h.genres, rating = h.rating, leading = h.leading, quality = h.quality)
                Spacer(Modifier.height(6.dp))
                Text(
                    (h.plot ?: fetched).orEmpty(), fontSize = 13.sp, color = Palette.Dim, maxLines = 2,
                    lineHeight = 18.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(36.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton(
                        h.primaryLabel, h.primary, Modifier.focusRequester(primaryFocus),
                        primary = true, icon = h.primaryIcon,
                    )
                    ActionButton("Plus d'infos", h.info, icon = FluxIcons.Info)
                }
            }
        }
    }
}

/** Fond fixe (pas de vidéo ici) : image de l'élément sélectionné, changée seulement à l'arrêt. */
@Composable
private fun BrowseBackdrop(hero: () -> Hero?) {
    val image = rememberSettledValue(320, { it }) { hero()?.image }
    Backdrop(image)
}

// ---------------------------------------------------------------- Rangées

@Composable
private fun SeeAllCard(onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.revealOnFocus().width(132.dp)) {
        FocusSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused },
            onLongClick = onLongClick,
            focusedScale = 1.08f,
            containerColor = Color.White.copy(alpha = 0.05f),
        ) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Tout voir", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (focused) Color.White else Palette.Dim,
                )
                Text("›", fontSize = 30.sp, color = if (focused) Color.White else Palette.Dim)
            }
        }
    }
}

/** Rangée horizontale (comme [shelf]) terminée par une carte « Tout voir ». */
private fun <T> LazyListScope.browseShelf(
    id: String,
    title: String,
    items: List<T>,
    state: LazyListState,
    key: (T) -> Any,
    onSeeAll: (() -> Unit)?,
    onSeeAllLong: (() -> Unit)? = null,
    seeAllModifier: Modifier = Modifier,
    nav: RowNav? = null,
    card: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    val rowIndex = nav?.nextIndex() ?: -1
    item(key = id, contentType = "shelf") {
        if (!LocalIsTv.current) {
            // Téléphone : « Tout voir › » touchable dans le titre (appui long sur une catégorie : rien).
            PhoneShelfRow(title, onSeeAll, state) {
                items(items.size, key = { key(items[it]) }, contentType = { 0 }) { i -> card(items[i]) }
            }
            return@item
        }
        // Comme sur l'accueil : la rangée est UN bloc pour ▲▼ ([RowNav]) : toujours la rangée voisine, jamais
        // plus loin, même si elle n'est pas encore composée ; la dernière vignette visitée est retrouvée.
        val rowFocus = remember { FocusRequester() }
        if (nav != null) {
            androidx.compose.runtime.DisposableEffect(rowIndex) {
                nav.requesters[rowIndex] = rowFocus
                onDispose { if (nav.requesters[rowIndex] === rowFocus) nav.requesters.remove(rowIndex) }
            }
        }
        Column(
            if (nav == null) Modifier else Modifier
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> nav.move(rowIndex, 1)
                        Key.DirectionUp -> nav.move(rowIndex, -1)
                        else -> false
                    }
                }
                .focusRequester(rowFocus)
                .focusGroup()
        ) {
            SectionTitle(title, Modifier.padding(start = 40.dp, bottom = 10.dp))
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPaddedScrollSpec(40.dp)) {
                LazyRow(
                    state = state,
                    modifier = Modifier.focusRestorer(),
                    contentPadding = PaddingValues(start = 40.dp, end = 56.dp, top = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(items.size, key = { key(items[it]) }, contentType = { 0 }) { i -> card(items[i]) }
                    if (onSeeAll != null) {
                        item(key = "__seeall", contentType = 1) { SeeAllCard(onSeeAll, onSeeAllLong, seeAllModifier) }
                    }
                }
            }
        }
    }
}

/**
 * Onglets de la page (À la une | Genres | Catégories | A–Z), à la même place sur toutes les pages.
 * [onDown] : ▼ personnalisé (page « À la une » : on va à la première rangée, pas aux boutons du bandeau).
 */
@Composable
private fun ModePillRow(
    mode: String,
    pillFocus: FocusRequester,
    startPad: Dp,
    onMode: (String) -> Unit,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onDown: (() -> Boolean)? = null,
) {
    val pills = if (mode == MODE_SAGAS) MODE_PILLS + (MODE_SAGAS to "Sagas") else MODE_PILLS
    LazyRow(
        modifier = Modifier
            .then(if (onFocusChange != null) Modifier.onFocusChanged { onFocusChange(it.hasFocus) } else Modifier)
            .then(
                if (onDown == null) Modifier else Modifier.onPreviewKeyEvent { e ->
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && onDown()
                }
            ),
        contentPadding = PaddingValues(start = startPad, end = 16.dp, top = if (LocalIsTv.current) 18.dp else 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(pills, key = { it.first }) { (m, label) ->
            Pill(
                label, mode == m, onClick = { onMode(m) },
                modifier = if (m == mode) Modifier.focusRequester(pillFocus) else Modifier,
            )
        }
    }
}

/** Demande le focus, en réessayant quelques images (le temps que la vue soit composée). */
private suspend fun tryFocus(r: FocusRequester): Boolean {
    repeat(8) {
        delay(32)
        if (runCatching { r.requestFocus() }.isSuccess) return true
    }
    return false
}

private const val FOCUS_PILL = 1
private const val FOCUS_RETURN = 2

/** Comportement d'une section (films ou séries). */
internal class BrowseSpec<T>(
    val title: String,
    val icon: ImageVector,
    val unit: String,
    val hideKind: String,
    val key: (T) -> String,
    val name: (T) -> String,
    val poster: (T) -> String?,
    val quality: (T) -> Quality,
    val hero: (T, String) -> Hero,
    val open: (T) -> Unit,
    /** Menu d'actions d'une vignette (appui long), calculé à l'ouverture. */
    val menu: ((T) -> List<CardAction>)?,
)

/**
 * Section Films / Séries : page « À la une » (rangées) ou grille (Genres, Catégories, A–Z, Sagas).
 * [topRows] (reprise, nouveaux épisodes) et [tmdbRows] (listes TMDB, « Parce que tu as regardé ») sont
 * fournis par l'écran.
 */
@Composable
private fun <T> BrowseSection(
    spec: BrowseSpec<T>,
    catalog: Catalog,
    allItems: List<T>,
    providerCategories: List<String>,
    byCat: Map<String, List<T>>,
    data: BrowseData<T>?,
    topRows: List<BrowseRow<T>>,
    tmdbRows: List<BrowseRow<T>>,
    initialHero: Hero?,
    progress: (T) -> Float?,
    badge: (T) -> String?,
    cell: @Composable (item: T, onFocus: () -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(MODE_FEATURED) }
    var gridSel by rememberSaveable { mutableStateOf<String?>(null) }
    var gridTick by rememberSaveable { mutableIntStateOf(0) }
    // Rangée d'où « Tout voir » a été ouvert : le focus y revient au retour sur « À la une ».
    var returnRow by remember { mutableStateOf<String?>(null) }
    var pendingFocus by remember { mutableIntStateOf(0) }
    var focusGrid by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }

    val listState = if (LocalIsTv.current) remember { rowsListState() } else rememberLazyListState()
    // TV : navigation ▲▼ explicite entre les rangées (null au toucher).
    val tvRowNav = rememberRowNav(listState)
    val rowNav = if (LocalIsTv.current) tvRowNav else null
    val rowStates = remember { HashMap<String, LazyListState>() }
    fun rowState(id: String) = rowStates.getOrPut(id) { LazyListState() }

    // Jamais lu dans le corps de l'écran : seulement dans BrowseHero / BrowseBackdrop (via heroNow).
    val heroState: MutableState<Hero?> = remember { mutableStateOf(null) }
    val initialState = rememberUpdatedState(initialHero)
    val heroNow: () -> Hero? = remember { { heroState.value ?: initialState.value } }

    val primaryFocus = remember { FocusRequester() }
    val pillFocus = remember { FocusRequester() }
    val returnFocus = remember { FocusRequester() }
    val gridEntry = remember { FocusRequester() }
    // Première vignette de la première rangée : focus à l'arrivée et cible de ▼ depuis les onglets.
    val firstCard = remember { FocusRequester() }
    val pillsFocused = remember { BooleanArray(1) }

    fun switchTo(m: String, sel: String?, fromRow: String?) {
        returnRow = fromRow
        gridSel = sel
        focusGrid = fromRow != null
        pendingFocus = if (fromRow == null) FOCUS_PILL else 0
        gridTick++
        mode = m
    }

    val isTv = LocalIsTv.current
    val compactWidth = rememberIsCompact()
    val overlayOpen = LocalOverlayOpen.current
    // Téléphone : pas de focus ; le retour revient à « À la une » tant qu'aucune fiche n'est ouverte par-dessus.
    BackHandler(enabled = mode != MODE_FEATURED && (hasFocus || (!isTv && !overlayOpen))) {
        pendingFocus = if (returnRow != null) FOCUS_RETURN else FOCUS_PILL
        mode = MODE_FEATURED
    }
    LaunchedEffect(mode, gridTick) {
        when (pendingFocus) {
            FOCUS_PILL -> tryFocus(pillFocus)
            FOCUS_RETURN -> if (!tryFocus(returnFocus)) tryFocus(primaryFocus)
        }
        pendingFocus = 0
    }

    // Arrivée sur la page (TV) : le focus tombe d'abord sur les onglets ; on le pose sur la première vignette
    // (un OK réflexe ouvre alors une fiche, il ne lance pas un film). Les onglets restent atteignables avec ▲.
    if (isTv) {
        LaunchedEffect(Unit) {
            if (mode != MODE_FEATURED) return@LaunchedEffect
            repeat(40) {
                delay(40)
                if (pillsFocused[0]) {
                    if (runCatching { firstCard.requestFocus() }.getOrDefault(false) == true) return@LaunchedEffect
                } else if (hasFocus) return@LaunchedEffect // le focus est déjà ailleurs dans la page
            }
        }
    }

    // Rangées de la page « À la une » (communes à la TV et au téléphone).
    val enriching by MetaEnricher.running.collectAsState()
    // Tactile : ordre des rangées de genres / catégories figé pour la session (une rangée montrée garde sa place,
    // les nouvelles s'ajoutent à la fin) : rien ne bouge sous le doigt pendant le classement par genre.
    // Genres et catégories forment un seul groupe ordonné (placé après « Sagas ») : un genre classé plus tard
    // arrive en bas, il ne pousse pas les catégories déjà affichées.
    val rowOrder = remember { ArrayList<String>() }
    val touchRows = if (isTv) emptyList()
    else remember(data) { HomeRows.stableOrder(rowOrder, data?.genres.orEmpty() + data?.categories.orEmpty()) { it.id } }
    val shownGenres = if (isTv) data?.genres.orEmpty() else emptyList()
    val shownCategories = if (isTv) data?.categories.orEmpty() else touchRows
    val featuredRows: LazyListScope.() -> Unit = {
                            // Première rangée affichée (dans l'ordre d'affichage) : sa première vignette porte [firstCard].
                            val firstRowId = (topRows + data?.head.orEmpty() + tmdbRows)
                                .firstOrNull { it.items.isNotEmpty() }?.id
                                ?: data?.genres?.firstOrNull { it.items.isNotEmpty() }?.id
                            fun rows(list: List<BrowseRow<T>>) {
                                for (row in list) {
                                    val seeAll = row.seeAll
                                    val cat = row.category
                                    // « Tout voir » : capture le genre / la catégorie de CETTE rangée (pas sa position).
                                    val seeMode = seeAll?.mode
                                    val seeSel = seeAll?.selection
                                    val rowId = row.id
                                    browseShelf(
                                        row.id, row.title, row.items, rowState(row.id), spec.key,
                                        onSeeAll = seeMode?.let { md -> { switchTo(md, seeSel, rowId) } },
                                        onSeeAllLong = cat?.let { c ->
                                            {
                                                HiddenCategories.set(ctx, spec.hideKind, c, hide = true)
                                                android.widget.Toast.makeText(
                                                    ctx, "« ${categoryLabel(c)} » masquée · Réglages pour la réafficher",
                                                    android.widget.Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        },
                                        seeAllModifier = if (row.id == returnRow) Modifier.focusRequester(returnFocus) else Modifier,
                                        nav = rowNav,
                                    ) { t ->
                                        PosterCard(
                                            displayTitle(spec.name(t)), spec.poster(t), spec.quality(t),
                                            modifier = if (row.id == firstRowId && row.items.firstOrNull() == t) Modifier.focusRequester(firstCard) else Modifier,
                                            onClick = { spec.open(t) },
                                            menu = spec.menu?.let { mk -> { mk(t) } },
                                            progress = (row.progress ?: progress)(t),
                                            subtitle = row.subtitle?.invoke(t),
                                            badge = badge(t),
                                            onFocus = { heroState.value = spec.hero(t, row.title) },
                                        )
                                    }
                                }
                            }
                            rows(topRows)
                            if (data != null) rows(data.head)
                            rows(tmdbRows)
                            if (data != null) {
                                rows(shownGenres)
                                val sagas = data.sagas
                                browseShelf(
                                    "sagas", "Sagas", sagas, rowState("sagas"), key = { it.first },
                                    onSeeAll = { switchTo(MODE_SAGAS, null, "sagas") },
                                    seeAllModifier = if (returnRow == "sagas") Modifier.focusRequester(returnFocus) else Modifier,
                                    nav = rowNav,
                                ) { s ->
                                    val (name, films) = s
                                    val open = { switchTo(MODE_SAGAS, name, "sagas") }
                                    val sagaTitle = displayTitle(name)
                                    PosterCard(
                                        sagaTitle, films.firstNotNullOfOrNull(spec.poster), null,
                                        onClick = open,
                                        subtitle = "${films.size} films",
                                        onFocus = {
                                            val years = films.joinToString("  •  ") {
                                                DisplayTitle.year(spec.name(it))?.toString() ?: displayTitle(spec.name(it))
                                            }.take(120)
                                            heroState.value = Hero(
                                                "saga-$name", "Saga · ${films.size} films", sagaTitle,
                                                years, null, films.firstNotNullOfOrNull(spec.poster), null,
                                                "Voir la saga", FluxIcons.Play, primary = open, info = open,
                                                leading = years,
                                            )
                                        },
                                    )
                                }
                                rows(shownCategories)
                                if (enriching && data.genres.isEmpty()) {
                                    item(key = "__enriching") {
                                        Text(
                                            "Classement par genre en cours… (${data.genreIndex.analysed} analysés)",
                                            fontSize = 12.sp, color = Palette.Accent,
                                            modifier = Modifier.padding(start = screenPadStart()),
                                        )
                                    }
                                }
                            }
    }

    // ---- Téléphone / tablette, page « À la une » : titre, pastilles balayables, grande carte fixe
    // (élément vedette), puis les mêmes rangées au doigt. Les grilles (Genres, Catégories…) sont communes.
    if (!isTv && mode == MODE_FEATURED) {
        // Nouvel appui sur la section active (rail / barre du bas) : retour en haut de la page.
        ScrollToTopEffect { listState.animateScrollToItem(0) }
        Column(Modifier.fillMaxSize()) {
            PhoneTopBar(null, null, title = spec.title)
            // Onglets épinglés au-dessus de la liste (comme sur les grilles).
            LazyRow(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((m, label) in MODE_PILLS) {
                    item(key = m) { Pill(label, mode == m, onClick = { switchTo(m, null, null) }) }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val h = initialHero
                if (h != null) {
                    item(key = "__phone_hero", contentType = "hero") {
                        PhoneHeroCard(
                            eyebrow = h.eyebrow, title = h.title, meta = h.meta, image = h.image, quality = h.quality,
                            primaryLabel = h.primaryLabel, onPrimary = h.primary,
                            secondary = { m -> ActionButton("Infos", h.info, m, icon = FluxIcons.Info) },
                            onImageClick = h.info,
                            tmdb = h.tmdb,
                        )
                    }
                }
                if (allItems.isEmpty()) item(key = "__empty") { EmptyState(spec.icon, "Aucun contenu") }
                else featuredRows()
                item(key = "__phone_bottom", contentType = "bottom") { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }) {
        if (mode == MODE_FEATURED) {
            BrowseBackdrop(heroNow)
            Column(Modifier.fillMaxSize()) {
                ModePillRow(
                    mode, pillFocus, startPad = 40.dp, onMode = { switchTo(it, null, null) },
                    onFocusChange = { pillsFocused[0] = it },
                    // ▼ depuis les onglets : première rangée (les boutons du bandeau s'atteignent avec ▲ depuis elle).
                    onDown = { runCatching { firstCard.requestFocus() }.getOrDefault(false) == true },
                )
                BrowseHero(heroNow, primaryFocus)
                if (allItems.isEmpty()) {
                    EmptyState(spec.icon, "Aucun contenu")
                } else {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides NoVerticalScrollSpec) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(top = 0.dp, bottom = 48.dp),
                            verticalArrangement = Arrangement.spacedBy(22.dp),
                        ) {
                            listTopAnchor(rowNav)
                            featuredRows()
                            listBottomRoom()
                        }
                    }
                }
            }
        } else {
            val m = mode
            CategoryBrowser(
                title = spec.title,
                icon = spec.icon,
                unit = spec.unit,
                catalog = catalog,
                categories = when (m) {
                    MODE_GENRES -> data?.genreIndex?.genres.orEmpty()
                    MODE_AZ -> data?.azLetters.orEmpty()
                    MODE_SAGAS -> data?.sagas?.map { it.first }.orEmpty()
                    else -> providerCategories
                },
                hideKind = if (m == MODE_CATEGORIES) spec.hideKind else null,
                selectionKey = "$m|$gridTick",
                initialSelection = gridSel,
                dataKey = m to data,
                entryFocus = if (focusGrid) gridEntry else null,
                hint = if (m == MODE_GENRES && enriching) {
                    "Classement par genre en cours… (${data?.genreIndex?.analysed ?: 0} analysés)"
                } else null,
                tabs = { ModePillRow(m, pillFocus, startPad = 0.dp, onMode = { switchTo(it, null, null) }) },
                label = if (m == MODE_CATEGORIES) ::categoryLabel else { s -> s },
                itemsFor = { c ->
                    when (m) {
                        MODE_GENRES -> if (c == null) allItems else data?.genreIndex?.byGenre?.get(c).orEmpty()
                        MODE_AZ -> if (c == null) data?.az ?: allItems else data?.azByLetter?.get(c).orEmpty()
                        MODE_SAGAS -> if (c == null) data?.sagas?.flatMap { it.second }.orEmpty()
                        else data?.sagas?.firstOrNull { it.first == c }?.second.orEmpty()
                        else -> if (c == null) allItems else byCat[c].orEmpty()
                    }
                },
                minCell = if (isTv) 132.dp else if (compactWidth) 110.dp else 144.dp,
                key = spec.key,
                backdropFor = spec.poster,
            ) { t, _, _, onFocus -> cell(t, onFocus) }
        }
    }
}

// ---------------------------------------------------------------- Écrans

@Composable
fun MoviesScreen(catalog: Catalog, onOpenMovie: (Movie) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { ProgressStore.get(ctx) }
    val settings = remember { Settings(ctx) }
    val version by ProgressStore.version.collectAsState()
    val profile by com.fluxfr.tv.data.Profiles.current.collectAsState()
    // SQLite hors du thread principal ; valeur précédente conservée pendant le rechargement (jamais celle d'un autre profil).
    val progressLoaded by produceState(Pair<String, Map<String, Progress>>(profile, emptyMap()), version, profile) {
        val forProfile = profile
        value = forProfile to withContext(Dispatchers.IO) { store.movies() }
    }
    val progress = if (progressLoaded.first == profile) progressLoaded.second else emptyMap()

    val resume = remember(progress, catalog) {
        progress.values.asSequence()
            .filter { !it.finished && it.position > 30_000 }
            .sortedByDescending { it.updated }
            .mapNotNull { catalog.movieById[it.itemId] }
            .distinctBy { it.id }
            .take(ROW_MAX).toList()
    }
    val reco = rememberRecoRows(catalog, RecoKind.MOVIE)
    val topRows = remember(resume, reco) {
        listOf(
            BrowseRow("resume", "Reprendre", resume),
            BrowseRow("foryou", "Pour toi", reco.forYou.mapNotNull { (it as? RecoItem.M)?.movie }),
        )
    }

    // TMDB : tendances, populaires, mieux notés, au cinéma (cache 6 h), retrouvés dans le catalogue.
    val tmdb by produceState(TmdbLists.peekMovieShelves(catalog), catalog) {
        runCatching { TmdbLists.movieShelves(ctx.applicationContext, catalog) }.getOrNull()?.let { value = it }
    }
    val tmdbRows = remember(tmdb, reco) {
        tmdbRows(tmdb) { it.id } + reco.because.map { (seed, items) ->
            BrowseRow("because-$seed", "Parce que tu as regardé « ${displayTitle(seed)} »", items.mapNotNull { (it as? RecoItem.M)?.movie })
        }
    }

    val data = rememberBrowseData("movies", catalog) {
        buildBrowseData(
            catalog.movies, catalog.movieCategories, catalog.moviesByCat,
            metaKey = { VodMeta.movieKey(it.id) },
            added = { it.added },
            sortKey = { it.key },
            isUhd = { it.quality == Quality.UHD },
            sagas = runCatching { Sagas.index(catalog.movies, ctx).sagas.take(MAX_SAGAS).map { it.name to it.movies } }
                .getOrDefault(emptyList()),
            reservedRows = 2 + MAX_TMDB_ROWS + MAX_BECAUSE_ROWS,
        )
    }

    val movieMenu: (Movie) -> List<CardAction> = { m ->
        movieActions(
            ctx, m, progress[m.id],
            onInfo = { onOpenMovie(m) },
            play = { mode -> PlayerSession.start(ctx, listOf(Playables.movie(m)), 0, mode) },
        )
    }
    val hero: (Movie, String) -> Hero = { m, eyebrow ->
        val meta = VodMeta.get(VodMeta.movieKey(m.id))
        val resumable = progress[m.id]?.let { !it.finished && it.position > 10_000 } == true
        val year = meta?.year ?: DisplayTitle.year(m.name)
        val genres = HomeRows.heroGenres(meta?.genres, m.category)
        Hero(
            "m${m.id}", eyebrow, HomeRows.shownTitle(m.name),
            HomeRows.metaText(null, year, genres, m.rating),
            null, m.poster, m.quality,
            if (resumable) "Reprendre" else "Lecture", FluxIcons.Play,
            loadPlot = if (settings.mode == Settings.MODE_XTREAM) {
                { XtreamClient(settings.server, settings.user, settings.pass).vodInfo(m.id).plot?.takeIf { it.isNotBlank() } }
            } else null,
            // « Reprendre » : reprise directe (choix déjà fait), sans seconde invite dans le lecteur.
            primary = { PlayerSession.start(ctx, listOf(Playables.movie(m)), 0, if (resumable) StartMode.RESUME else StartMode.ASK) },
            info = { onOpenMovie(m) },
            year = year, genres = genres, rating = m.rating,
            tmdb = TmdbRef.movie(m.id),
        )
    }
    val spec = remember(progress, settings) {
        BrowseSpec<Movie>(
            "Films", FluxIcons.Movie, "film", HiddenCategories.MOVIE,
            key = { it.id }, name = { it.name }, poster = { it.poster }, quality = { it.quality },
            hero = hero, open = onOpenMovie, menu = movieMenu,
        )
    }
    // Bandeau rempli dès l'arrivée (avant tout focus, et sans attendre le calcul des rangées) : la même vignette
    // que la première rangée affichée quand c'est possible, sinon le titre le plus récent du catalogue.
    val newest = remember(catalog) { catalog.movies.maxByOrNull { it.added } }
    val initial = remember(resume, topRows, data, tmdbRows, newest) {
        topRows.firstOrNull { it.items.isNotEmpty() }?.let { r -> hero(r.items.first(), r.title) }
            ?: data?.head?.firstOrNull { it.items.isNotEmpty() }?.let { r -> hero(r.items.first(), r.title) }
            ?: newest?.let { hero(it, "Nouveautés") }
            ?: tmdbRows.firstOrNull { it.items.isNotEmpty() }?.let { r -> hero(r.items.first(), r.title) }
    }
    val fraction: (Movie) -> Float? = { m -> progress[m.id]?.takeUnless { it.finished }?.fraction }

    BrowseSection(
        spec, catalog, catalog.movies, catalog.movieCategories, catalog.moviesByCat, data,
        topRows, tmdbRows, initial,
        progress = fraction,
        badge = { null },
    ) { m, onFocus ->
        GridPosterCard(
            m.name, m.poster, m.quality,
            onClick = { onOpenMovie(m) },
            menu = { movieMenu(m) },
            progress = fraction(m),
            onFocus = onFocus,
        )
    }
}

@Composable
fun SeriesScreen(catalog: Catalog, onOpenSeries: (Series) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { ProgressStore.get(ctx) }
    val version by ProgressStore.version.collectAsState()
    val profile by com.fluxfr.tv.data.Profiles.current.collectAsState()

    // Séries en cours (dernier épisode regardé), limitées au catalogue affiché (profil Enfants compris).
    // Lecture SQLite (et synchro déclenchée par continueWatching) hors du thread principal.
    val resumedLoaded by produceState(Pair<String, List<Pair<Series, Progress>>>(profile, emptyList()), version, catalog, profile) {
        val forProfile = profile
        value = forProfile to withContext(Dispatchers.IO) {
            store.continueWatching(80)
                .filter { it.type == Progress.EPISODE && it.seriesId != null }
                .mapNotNull { p -> catalog.seriesById[p.seriesId]?.let { it to p } }
                .distinctBy { it.first.id }
                .take(ROW_MAX)
        }
    }
    val resumed = if (resumedLoaded.first == profile) resumedLoaded.second else emptyList()
    val newEpisodes = rememberNewEpisodeIds(catalog)
    val reco = rememberRecoRows(catalog, RecoKind.SERIES)
    val topRows = remember(resumed, newEpisodes, catalog, reco) {
        val byId = resumed.associate { it.first.id to it.second }
        listOf(
            BrowseRow(
                "resume", "Reprendre", resumed.map { it.first },
                subtitle = { s -> byId[s.id]?.let { p -> if (p.finished) "Épisode suivant" else p.subtitle } },
                progress = { s -> byId[s.id]?.takeUnless { it.finished }?.fraction },
            ),
            BrowseRow("foryou", "Pour toi", reco.forYou.mapNotNull { (it as? RecoItem.S)?.series }),
            BrowseRow(
                "newep", "Nouveaux épisodes",
                if (newEpisodes.isEmpty()) emptyList() else catalog.series.filter { it.id in newEpisodes }.take(ROW_MAX),
            ),
        )
    }

    // TMDB : tendances, populaires, mieux notées, en cours de diffusion (cache 6 h), retrouvées dans le catalogue.
    val tmdb by produceState(TmdbLists.peekSeriesShelves(catalog), catalog) {
        runCatching { TmdbLists.seriesShelves(ctx.applicationContext, catalog) }.getOrNull()?.let { value = it }
    }
    val tmdbRows = remember(tmdb, reco) {
        tmdbRows(tmdb) { it.id } + reco.because.map { (seed, items) ->
            BrowseRow("because-$seed", "Parce que tu as regardé « ${displayTitle(seed)} »", items.mapNotNull { (it as? RecoItem.S)?.series })
        }
    }

    val data = rememberBrowseData("series", catalog) {
        buildBrowseData(
            catalog.series, catalog.seriesCategories, catalog.seriesByCat,
            metaKey = { VodMeta.seriesKey(it.id) },
            added = { it.added },
            sortKey = { it.key },
            isUhd = null,
            sagas = emptyList(),
            reservedRows = 3 + MAX_TMDB_ROWS + MAX_BECAUSE_ROWS,
        )
    }

    val hero: (Series, String) -> Hero = { s, eyebrow ->
        val meta = VodMeta.get(VodMeta.seriesKey(s.id))
        val year = meta?.year ?: DisplayTitle.year(s.name)
        val genres = HomeRows.heroGenres(meta?.genres, s.category)
        Hero(
            "s${s.id}", eyebrow, HomeRows.shownTitle(s.name),
            HomeRows.metaText(null, year, genres, null),
            s.plot?.takeIf { it.isNotBlank() }, s.poster, s.quality,
            "Voir les épisodes", FluxIcons.Play,
            primary = { onOpenSeries(s) },
            info = { onOpenSeries(s) },
            year = year, genres = genres,
            tmdb = TmdbRef.series(s.id),
        )
    }
    val spec = remember {
        BrowseSpec<Series>(
            "Séries", FluxIcons.Series, "série", HiddenCategories.SERIES,
            key = { it.id }, name = { it.name }, poster = { it.poster }, quality = { it.quality },
            hero = hero, open = onOpenSeries,
            menu = { s -> seriesActions(ctx, s, onOpen = { onOpenSeries(s) }) },
        )
    }
    // Bandeau rempli dès l'arrivée (voir MoviesScreen).
    val newest = remember(catalog) { catalog.series.maxByOrNull { it.added } }
    val initial = remember(topRows, data, tmdbRows, newest) {
        topRows.firstOrNull { it.items.isNotEmpty() }?.let { r -> hero(r.items.first(), r.title) }
            ?: data?.head?.firstOrNull { it.items.isNotEmpty() }?.let { r -> hero(r.items.first(), r.title) }
            ?: newest?.let { hero(it, "Nouveautés") }
            ?: tmdbRows.firstOrNull { it.items.isNotEmpty() }?.let { r -> hero(r.items.first(), r.title) }
    }
    val badge: (Series) -> String? = { s -> if (s.id in newEpisodes) NEW_EPISODE_BADGE else null }

    BrowseSection(
        spec, catalog, catalog.series, catalog.seriesCategories, catalog.seriesByCat, data,
        topRows, tmdbRows, initial,
        progress = { null },
        badge = badge,
    ) { s, onFocus ->
        GridPosterCard(
            s.name, s.poster, s.quality, onClick = { onOpenSeries(s) },
            menu = { seriesActions(ctx, s, onOpen = { onOpenSeries(s) }) },
            onFocus = onFocus,
            badge = badge(s),
        )
    }
}
