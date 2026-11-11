package com.fluxfr.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.Channel
import com.fluxfr.tv.data.DisplayTitle
import com.fluxfr.tv.data.Favorites
import com.fluxfr.tv.data.Quality
import com.fluxfr.tv.data.VodMeta
import com.fluxfr.tv.data.searchKey
import com.fluxfr.tv.player.Playables
import com.fluxfr.tv.player.PlayerSession
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------- Aides partagées (Films, Séries, fiches, recherche)

/** Titre affiché, mémorisé (les expressions régulières de [DisplayTitle] ne tournent qu'une fois par nom). */
private val displayTitleCache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 1500
}

internal fun displayTitle(name: String): String =
    synchronized(displayTitleCache) { displayTitleCache[name] } ?: DisplayTitle.of(name).also {
        synchronized(displayTitleCache) { displayTitleCache[name] = it }
    }

private val CATEGORY_NAMES = mapOf(
    "LATEST MOVIES" to "Nouveautés", "LATEST SERIES" to "Nouveautés", "LATEST" to "Nouveautés", "NEW" to "Nouveautés",
    "NEW RELEASES" to "Sorties récentes", "NEW RELEASE" to "Sorties récentes", "RECENT" to "Récents",
    "NEWS" to "Infos", "ENTERTAINMENT" to "Divertissement", "KIDS" to "Jeunesse", "CHILDREN" to "Jeunesse",
    "DOCUMENTARY" to "Documentaires", "DOCUMENTARIES" to "Documentaires", "DOCUMENTAIRE" to "Documentaires",
    "SPORTS" to "Sport", "SPORT" to "Sport", "MOVIES" to "Films", "MOVIE" to "Films", "SERIES" to "Séries",
    "TV SHOWS" to "Émissions", "MUSIC" to "Musique", "ACTION" to "Action", "COMEDY" to "Comédie",
    "DRAMA" to "Drame", "HORROR" to "Horreur", "THRILLER" to "Thriller", "ANIMATION" to "Animation",
    "ANIMATED" to "Animation", "ROMANCE" to "Romance", "FAMILY" to "Famille", "CRIME" to "Policier",
    "ADVENTURE" to "Aventure", "FANTASY" to "Fantastique", "SCIENCE FICTION" to "Science-fiction",
    "SCI-FI" to "Science-fiction", "WAR" to "Guerre", "HISTORY" to "Histoire", "MYSTERY" to "Mystère",
    "RELIGIOUS" to "Religion", "ADULT" to "Adulte", "GENERAL" to "Généralistes", "LOCAL" to "Locales",
    "CINEMA" to "Cinéma", "MUSIC VIDEOS" to "Clips", "LIFESTYLE" to "Art de vivre", "WEATHER" to "Météo",
)
private val LANG_CODE_PREFIX = Regex(
    """^\s*(?:LANG|FR|EN|VF|VO|VOSTFR|MULTI|DE|ES|IT|PT|AR|TR|US|UK|NL|PL|RU)\s*[-–—|:]\s+""",
    RegexOption.IGNORE_CASE,
)
private val categoryLabelCache = ConcurrentHashMap<String, String>()

/**
 * Nom affiché d'une catégorie du fournisseur : préfixe de langue retiré (« LANG - … »), libellés anglais
 * courants traduits. La clé de la catégorie (utilisée pour filtrer, masquer) n'est jamais modifiée.
 */
internal fun categoryLabel(raw: String): String = categoryLabelCache.getOrPut(raw) {
    var s = raw.trim()
    s = LANG_CODE_PREFIX.replace(s, "").trim().trim('-', '–', '—', '|', ':').trim()
    if (s.isBlank()) return@getOrPut raw
    CATEGORY_NAMES[searchKey(s).uppercase()]?.let { return@getOrPut it }
    val letters = s.filter { it.isLetter() }
    if (letters.length > 3 && s == s.uppercase()) {
        s = s.split(' ').joinToString(" ") { w ->
            if (w.length <= 3 || w.any { it.isDigit() }) w else w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
    s
}

/** 15671 → « 15 671 ». */
internal fun formatCount(n: Int): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val sb = StringBuilder()
    s.forEachIndexed { i, c ->
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(' ')
        sb.append(c)
    }
    return sb.toString()
}

/** Fondu à droite d'une rangée de pastilles : plus rien n'est coupé net au bord de l'écran. */
internal fun Modifier.fadeEnd(fadeWidth: Dp = 56.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = fadeWidth.toPx().coerceAtMost(size.width)
        drawRect(
            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
            topLeft = Offset(size.width - w, 0f),
            size = Size(w, size.height),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Défilement d'une rangée qui garde [pad] de marge : l'élément sélectionné (affiche agrandie, titre qui défile)
 * n'est jamais collé au bord de l'écran.
 */
@Composable
internal fun rememberPaddedScrollSpec(pad: Dp = 40.dp): BringIntoViewSpec {
    val px = with(LocalDensity.current) { pad.toPx() }
    return remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val lead = offset - px
                val trail = offset + size - (containerSize - px)
                return when {
                    lead < 0f -> lead
                    trail > 0f -> if (size > containerSize - 2 * px) lead else trail
                    else -> 0f
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Vignette de grille (légère)

private const val GRID_PLACEHOLDER = 0xFF1B1F2E.toInt()

/**
 * Affiche de grille, pensée pour les très grandes listes (A–Z, ~15 000 films) : taille fixe (la cellule impose
 * le format 2:3), requête d'image à la taille de la cellule (RGB565 autorisé, pas de fondu, couleur d'attente),
 * état de focus local à la cellule (un déplacement du focus ne recompose que deux cellules), titre mémorisé.
 */
@Composable
internal fun GridPosterCard(
    name: String,
    image: String?,
    quality: Quality?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    badge: String? = null,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    minCell: Dp = 132.dp,
    /** Menu d'actions (appui long) : voir [PosterCard]. Remplace [onLongClick] quand non nul. */
    menu: (() -> List<CardAction>)? = null,
) {
    val title = displayTitle(name)
    var focused by remember { mutableStateOf(false) }
    val isTv = LocalIsTv.current
    val menuState = if (menu != null) rememberCardMenuState() else null
    val longClick: (() -> Unit)? = if (menuState != null) remember(menuState, isTv) { { menuState.requestOpen(isTv) } } else onLongClick
    Column(modifier.revealOnFocus().fillMaxWidth()) {
        FocusSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }.cardMenuAnchor(menuState),
            onLongClick = longClick,
            focusedScale = 1.08f,
        ) {
            if (image.isNullOrBlank()) {
                Artwork(null, title, ContentScale.Crop)
            } else {
                val ctx = LocalContext.current
                val density = LocalDensity.current
                val w = with(density) { (minCell * 1.2f).roundToPx() }
                val request = remember(image, w) {
                    ImageRequest.Builder(ctx).data(image).size(w, w * 3 / 2)
                        .precision(Precision.INEXACT).allowRgb565(true).crossfade(false)
                        .placeholder(android.graphics.drawable.ColorDrawable(GRID_PLACEHOLDER))
                        .build()
                }
                AsyncImage(
                    model = request, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (quality != null) QualityBadge(quality, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (badge != null) AccentBadge(badge, Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 10.dp))
            if (progress != null && progress > 0f) ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.height(8.dp))
        CardTitle(title, focused)
        if (menuState != null && menu != null) CardMenuPopup(menuState, title, menu)
    }
}

// ---------------------------------------------------------------- Navigateur de catégories

/** Fond d'écran de la grille : lit l'image seulement ici (le focus ne recompose pas la grille). */
@Composable
private fun GridBackdrop(source: () -> String?) {
    val image = rememberSettledValue(320, { it }, source)
    Backdrop(image, Modifier.fillMaxWidth().height(340.dp).alpha(0.45f), widthFraction = 0.62f)
}

/**
 * Écran de navigation : onglets (même position sur toutes les pages), rangée de pastilles de catégories
 * (sélection au focus), petite ligne titre / compteur, puis grille de contenu. L'en-tête défile avec la grille,
 * ce qui laisse toute la hauteur de l'écran aux affiches.
 */
@Composable
fun <T> CategoryBrowser(
    title: String,
    icon: ImageVector,
    unit: String,
    /** Le catalogue d'origine : un rafraîchissement en arrière-plan met la grille à jour. */
    catalog: Catalog,
    categories: List<String>,
    itemsFor: (String?) -> List<T>,
    minCell: Dp,
    key: (T) -> Any,
    backdropFor: ((T) -> String?)? = null,
    /** Type pour le masquage des catégories (HiddenCategories.LIVE / MOVIE / SERIES). */
    hideKind: String? = null,
    /** Quand cette valeur change (ex. passage Genres / Catégories), la sélection revient à « Tout ». */
    selectionKey: Any? = null,
    /** Change quand [itemsFor] renvoie autre chose pour une même catégorie (ex. index des genres recalculé). */
    dataKey: Any? = null,
    /** Petite ligne d'information sous le titre. */
    hint: String? = null,
    /** Onglets de la page (À la une | Genres | …), affichés tout en haut ; sans eux, grand en-tête classique. */
    tabs: (@Composable () -> Unit)? = null,
    /** Catégorie présélectionnée (« Tout voir » d'une rangée) ; reprise à chaque changement de [selectionKey]. */
    initialSelection: String? = null,
    /** Si non nul : le focus est donné à la première vignette à l'ouverture (une fois par [selectionKey]). */
    entryFocus: FocusRequester? = null,
    /** Nom affiché d'une catégorie (la clé reste inchangée). */
    label: (String) -> String = { it },
    cell: @Composable (item: T, list: List<T>, index: Int, onFocus: () -> Unit) -> Unit,
) {
    val ctxHide = LocalContext.current
    val isTv = LocalIsTv.current
    var selected by rememberSaveable(selectionKey) { mutableStateOf(initialSelection) }
    val items = remember(selected, categories, catalog, dataKey) { itemsFor(selected) }
    // Lu seulement par GridBackdrop : un changement de focus ne recompose pas la grille.
    val backdropState = remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    // Rangée de pastilles calée sur la catégorie présélectionnée (▲ depuis la grille y retombe sans changer
    // la sélection) : « Tout », puis les catégories.
    val chipsState = remember(selectionKey) {
        val idx = initialSelection?.let { categories.indexOf(it) } ?: -1
        LazyListState(firstVisibleItemIndex = if (idx >= 0) 1 + idx else 0)
    }
    var entryDone by remember(selectionKey) { mutableStateOf(false) }
    if (entryFocus != null && !entryDone && items.isNotEmpty()) {
        LaunchedEffect(selectionKey) {
            repeat(8) {
                kotlinx.coroutines.delay(32)
                if (runCatching { entryFocus.requestFocus() }.isSuccess) { entryDone = true; return@LaunchedEffect }
            }
            entryDone = true
        }
    }

    // Tactile : re-toucher la section active remonte tout en haut de la grille.
    ScrollToTopEffect { gridState.animateScrollToItem(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val wide = !rememberIsCompact()
    // Lettres (A–Z) : sur grand écran, toutes visibles sur plusieurs lignes plutôt qu'une rangée coupée.
    val letterStrip = !isTv && wide && categories.isNotEmpty() && categories.all { it.length <= 2 }

    Box(Modifier.fillMaxSize()) {
        if (backdropFor != null && isTv) GridBackdrop { backdropState.value }

        Column(Modifier.fillMaxSize()) {
        // Tactile : les onglets (À la une | Genres | …) restent épinglés en haut pendant que la grille défile.
        if (!isTv && tabs != null) {
            Box(
                Modifier.fillMaxWidth().background(Palette.Bg)
                    .padding(start = screenPadStart(), end = screenPadEnd()),
            ) { tabs() }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minCell),
            state = gridState,
            modifier = (if (isTv) Modifier.fillMaxSize() else Modifier.weight(1f).fillMaxWidth()).focusRestorer(),
            contentPadding = PaddingValues(
                start = screenPadStart(), end = screenPadEnd(),
                top = if (!isTv) 12.dp else if (tabs != null) 0.dp else 32.dp, bottom = 48.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 24.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 26.dp else 16.dp),
        ) {
            if (tabs != null && isTv) {
                item(key = "__tabs", span = { GridItemSpan(maxLineSpan) }, contentType = "tabs") { tabs() }
            }
            if (tabs == null) {
                item(key = "__header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    BrowseHeader(title, icon, "${formatCount(items.size)} $unit${if (items.size > 1) "s" else ""}", selected?.let(label), hint)
                }
            }
            if (letterStrip) item(key = "__chips", span = { GridItemSpan(maxLineSpan) }, contentType = "chips") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Pill("Tout", selected == null, onClick = { selected = null })
                    for (c in categories) Pill(label(c), selected == c, onClick = { selected = c })
                }
            } else item(key = "__chips", span = { GridItemSpan(maxLineSpan) }, contentType = "chips") {
                LazyRow(
                    state = chipsState,
                    modifier = Modifier.fillMaxWidth().focusRestorer().fadeEnd(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 6.dp, start = 2.dp, end = 72.dp),
                ) {
                    item(key = "__all") {
                        Pill("Tout", selected == null, onClick = { selected = null }, onFocus = { selected = null })
                    }
                    items(categories, key = { it }) { c ->
                        Pill(
                            label(c), selected == c, onClick = { selected = c }, onFocus = { selected = c },
                            onLongClick = hideKind?.let { kind ->
                                {
                                    com.fluxfr.tv.data.HiddenCategories.set(ctxHide, kind, c, hide = true)
                                    if (selected == c) selected = null
                                    android.widget.Toast.makeText(
                                        ctxHide, "« ${label(c)} » masquée · Réglages pour la réafficher",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                        )
                    }
                }
            }
            if (tabs != null) {
                item(key = "__count", span = { GridItemSpan(maxLineSpan) }, contentType = "count") {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Box(Modifier.padding(horizontal = 8.dp).size(3.dp).background(Palette.Faint, CircleShape))
                            Text(
                                "${formatCount(items.size)} $unit${if (items.size > 1) "s" else ""}",
                                fontSize = 13.sp, color = Palette.Dim,
                            )
                            if (selected != null) {
                                Box(Modifier.padding(horizontal = 8.dp).size(3.dp).background(Palette.Faint, CircleShape))
                                Text(
                                    label(selected!!), fontSize = 13.sp, color = Palette.Faint, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (hint != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(hint, fontSize = 12.sp, color = Palette.Accent, maxLines = 1)
                        }
                    }
                }
            }
            if (items.isEmpty()) {
                item(key = "__empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    EmptyState(icon, "Aucun contenu dans cette catégorie")
                }
            } else {
                itemsIndexed(items, key = { _, it -> key(it) }, contentType = { _, _ -> "cell" }) { i, it ->
                    if (i == 0 && entryFocus != null) {
                        Box(Modifier.focusRequester(entryFocus)) {
                            cell(it, items, i) { if (backdropFor != null) backdropState.value = backdropFor(it) }
                        }
                    } else {
                        cell(it, items, i) { if (backdropFor != null) backdropState.value = backdropFor(it) }
                    }
                }
            }
        }
        }
        // Tactile : bouton « remonter » dès qu'on a défilé un peu loin.
        if (!isTv) {
            val showTop by remember { androidx.compose.runtime.derivedStateOf { gridState.firstVisibleItemIndex > 9 } }
            if (showTop) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(16.dp).size(48.dp).clip(CircleShape)
                        .background(Palette.Accent)
                        .semantics { contentDescription = "Remonter en haut" }
                        .clickable { scope.launch { gridState.animateScrollToItem(0) } },
                    contentAlignment = Alignment.Center,
                ) { Text("↑", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            }
        }
    }
}

/** Tactile : compteur incrémenté quand on re-touche la section active (App.kt) ; les écrans remontent en haut. */
internal val LocalScrollToTop = androidx.compose.runtime.compositionLocalOf { 0 }

/** Exécute [action] (remonter en haut d'une liste) à chaque nouveau signal [LocalScrollToTop] ; ignore la valeur initiale. */
@Composable
internal fun ScrollToTopEffect(action: suspend () -> Unit) {
    val tick = LocalScrollToTop.current
    val first = remember { tick }
    val act by androidx.compose.runtime.rememberUpdatedState(action)
    LaunchedEffect(tick) { if (tick != first) act() }
}

@Composable
private fun BrowseHeader(title: String, icon: ImageVector, count: String, category: String?, hint: String? = null) {
    Column(Modifier.padding(bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).background(Palette.AccentBrush, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) { FluxIcon(icon, size = 17.dp) }
            Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(count, fontSize = 13.sp, color = Palette.Dim)
            if (category != null) {
                Box(Modifier.padding(horizontal = 8.dp).size(3.dp).background(Palette.Faint, CircleShape))
                Text(
                    category, fontSize = 13.sp, color = Palette.Faint, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, fontSize = 12.sp, color = Palette.Accent, maxLines = 1)
        }
    }
}

@Composable
internal fun EmptyState(icon: ImageVector, message: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).background(Color.White.copy(alpha = 0.06f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { FluxIcon(icon, size = 28.dp, tint = Palette.Dim) }
        Spacer(Modifier.height(14.dp))
        Text(message, color = Palette.Dim, fontSize = 15.sp)
    }
}

/** Carte de chaîne : logo centré sur fond dégradé, nom dessous (pas de numéro : ceux du fournisseur repartent à chaque catégorie). */
@Composable
internal fun ChannelTile(
    ch: Channel,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.revealOnFocus().fillMaxWidth()) {
        FocusSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
            onLongClick = onLongClick,
            shape = RoundedCornerShape(14.dp),
            containerColor = Palette.CardHigh,
            focusedContainerColor = Color(0xFF2E3550),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent))
                )
            )
            Box(Modifier.fillMaxSize().padding(horizontal = if (LocalIsTv.current) 28.dp else 18.dp, vertical = if (LocalIsTv.current) 20.dp else 12.dp)) {
                if (ch.logo.isNullOrBlank()) {
                    Text(
                        ch.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    AsyncImage(
                        model = ch.logo, contentDescription = ch.name, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            QualityBadge(ch.quality, Modifier.align(Alignment.TopEnd).padding(7.dp))
            if (favorite) FavoriteBadge(Modifier.align(Alignment.TopStart).padding(7.dp))
        }
        Spacer(Modifier.height(8.dp))
        CardTitle(ch.name, focused)
        EpgNowLine(ch.id, focused)
    }
}

@Composable
fun LiveScreen(catalog: Catalog) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Favorites.load(ctx) }
    val favs by Favorites.items.collectAsState()
    CategoryBrowser(
        title = if (LocalIsTv.current) "En direct" else "Direct",
        icon = FluxIcons.Live,
        unit = "chaîne",
        catalog = catalog,
        categories = catalog.channelCategories,
        hideKind = com.fluxfr.tv.data.HiddenCategories.LIVE,
        label = ::categoryLabel,
        itemsFor = { c -> if (c == null) catalog.channels else catalog.channelsByCat[c].orEmpty() },
        minCell = if (LocalIsTv.current) 176.dp else 150.dp,
        key = { it.id },
    ) { ch, list, i, onFocus ->
        ChannelTile(
            ch,
            favorite = Favorites.liveKey(ch.id) in favs,
            onClick = { PlayerSession.start(ctx, Playables.channels(list), i) },
            onLongClick = { toggleChannelFavorite(ctx, ch.id) },
            onFocus = onFocus,
        )
    }
}

// ---------------------------------------------------------------- Index des genres (Films / Séries : BrowseRows.kt)

/** Un genre n'a sa pastille qu'à partir de ce nombre de titres. */
private const val MIN_GENRE_ITEMS = 3

/** Index des genres d'une liste (calculé hors du thread principal). */
internal class GenreIndex<T>(
    /** Genres ayant au moins [MIN_GENRE_ITEMS] titres, du plus fourni au moins fourni. */
    val genres: List<String>,
    val byGenre: Map<String, List<T>>,
    /** Nombre de titres déjà analysés (fiche VodMeta présente). */
    val analysed: Int,
)

internal fun <T> buildGenreIndex(items: List<T>, metaKey: (T) -> String): GenreIndex<T> {
    val byGenre = HashMap<String, ArrayList<T>>()
    var analysed = 0
    for (it in items) {
        val meta = VodMeta.get(metaKey(it)) ?: continue
        analysed++
        for (g in meta.genres) byGenre.getOrPut(g) { ArrayList() }.add(it)
    }
    val genres = byGenre.filterValues { it.size >= MIN_GENRE_ITEMS }.entries
        .sortedWith(compareByDescending<Map.Entry<String, ArrayList<T>>> { it.value.size }.thenBy { it.key })
        .map { it.key }
    return GenreIndex(genres, byGenre, analysed)
}
