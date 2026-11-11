package com.fluxfr.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.Profile
import com.fluxfr.tv.data.Profiles
import com.fluxfr.tv.data.Quality

// ================================================================ Téléphone / tablette (tactile)
//
// Rien ici n'est utilisé sur TV : la mise en page TV (rail à gauche, focus, D-pad) reste dans App.kt.

/** Icônes propres à l'interface tactile (tracés Material, licence Apache 2.0). */
object TouchIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White)).build()

    val More by lazy {
        icon(
            "more",
            "M6,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
        )
    }
    val Back by lazy { icon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z") }
    val VolumeOn by lazy {
        icon(
            "volumeOn",
            "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
        )
    }
    val VolumeOff by lazy {
        icon(
            "volumeOff",
            "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z",
        )
    }
    val ChevronRight by lazy { icon("chevron", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z") }
}

/** Empêche les touchers de traverser un calque posé par-dessus (fiche, menu, fenêtre). */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

/** Qui applique la marge du clavier : la racine ([TouchRoot]) tant que [PhoneMainLayout] ne s'en charge pas. */
internal class ImeInsetsHost {
    private val state = androidx.compose.runtime.mutableStateOf(false)
    var handledByLayout: Boolean
        get() = state.value
        set(v) { state.value = v }
}

internal val LocalImeHost = androidx.compose.runtime.staticCompositionLocalOf<ImeInsetsHost?> { null }

/**
 * Racine de l'appli tactile (bord à bord) : marges des barres système et de l'encoche à la racine ; la marge du
 * clavier n'est appliquée qu'à la zone de contenu par [PhoneMainLayout] (le rail et le fond descendent jusqu'en
 * bas, plus de bande sombre sous le contenu clavier ouvert). Écrans hors de cette mise en page (configuration…) :
 * marge du clavier à la racine, comme avant.
 */
@Composable
fun TouchRoot(content: @Composable () -> Unit) {
    val host = remember { ImeInsetsHost() }
    Box(
        Modifier.fillMaxSize().background(Palette.Bg)
            .windowInsetsPadding(
                androidx.compose.foundation.layout.WindowInsets.systemBars
                    .union(androidx.compose.foundation.layout.WindowInsets.displayCutout)
            )
            .then(if (host.handledByLayout) Modifier else Modifier.imePadding())
    ) {
        CompositionLocalProvider(LocalImeHost provides host) { content() }
    }
}

/** Sections de la barre du bas (le reste est dans « Plus »). */
private val BOTTOM_SECTIONS = listOf(Section.HOME, Section.MOVIES, Section.SERIES, Section.LIVE, Section.SEARCH)

private fun Section.shortLabel(): String = when (this) {
    Section.LIVE -> "Direct"
    else -> label
}

/**
 * Écran principal tactile : contenu de la section, fiche par-dessus, barre de navigation en bas
 * (téléphone) ou rail à gauche (tablette / paysage large), menu « Plus » et choix du profil.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PhoneMainLayout(
    sections: List<Section>,
    section: Section,
    detailOpen: Boolean,
    moreOpen: Boolean,
    profileDialog: Boolean,
    profile: Profile,
    refreshing: Boolean,
    refreshProgress: Float,
    onSelect: (Section) -> Unit,
    onMore: () -> Unit,
    onCloseMore: () -> Unit,
    onProfile: () -> Unit,
    onCloseProfile: () -> Unit,
    catalog: Catalog,
    content: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    /** Catalogue affiché (catégories masquées retirées) : compteurs identiques à ceux des pages. */
    countCatalog: Catalog = catalog,
) {
    val compact = rememberIsCompact()
    val bottom = BOTTOM_SECTIONS.filter { it in sections }
    val more = sections.filter { it !in BOTTOM_SECTIONS }
    // Marge du clavier prise ici, sur la seule zone de contenu (pas à la racine).
    val imeHost = LocalImeHost.current
    if (imeHost != null) {
        androidx.compose.runtime.DisposableEffect(imeHost) {
            imeHost.handledByLayout = true
            onDispose { imeHost.handledByLayout = false }
        }
    }
    CompositionLocalProvider(LocalOverlayOpen provides (detailOpen || moreOpen || profileDialog)) {
        Box(Modifier.fillMaxSize().background(Palette.Bg)) {
            val body: @Composable (Modifier) -> Unit = { m ->
                Box(m.then(if (imeHost != null) Modifier.imePadding() else Modifier)) {
                    content()
                    if (detailOpen) {
                        Box(Modifier.fillMaxSize().background(Palette.Bg).blockTouches()) { detail() }
                    }
                }
            }
            if (compact) {
                Column(Modifier.fillMaxSize()) {
                    body(Modifier.weight(1f).fillMaxWidth())
                    // Clavier ouvert (recherche…) : la barre du bas s'efface pour laisser la place aux résultats.
                    if (!androidx.compose.foundation.layout.WindowInsets.isImeVisible) {
                        PhoneBottomBar(bottom, section, detailOpen, moreSelected = section in more || moreOpen, onSelect, onMore)
                    }
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    PhoneNavRail(sections, section, detailOpen, profile, onSelect, onProfile)
                    body(Modifier.weight(1f).fillMaxHeight())
                }
            }

            // Mise à jour du catalogue en arrière-plan : fine barre en haut.
            if (refreshing) {
                val p by animateFloatAsState(refreshProgress.coerceIn(0f, 1f), tween(400), label = "refresh")
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.06f))) {
                    Box(Modifier.fillMaxWidth(p).height(3.dp).background(Palette.AccentBrush))
                }
            }

            // Menu « Plus » (téléphone) : Guide, TNT, Réglages, profil.
            AnimatedVisibility(moreOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCloseMore)
                )
            }
            AnimatedVisibility(
                moreOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                MoreSheet(more, section, profile, countCatalog, onSelect, onProfile)
            }

            // Choix du profil (par-dessus tout).
            if (profileDialog) {
                val w = (LocalConfiguration.current.screenWidthDp - 24).coerceAtLeast(280)
                Box(Modifier.fillMaxSize().blockTouches()) {
                    ModalPanel(minOf(if (Profiles.list.value.size > 3) 860 else 560, w).dp) {
                        Box(Modifier.verticalScroll(rememberScrollState())) {
                            ProfilePicker(
                                onChosen = { onCloseProfile() },
                                subtitle = "Un profil Enfants n'affiche que les catégories jeunesse.",
                            )
                        }
                    }
                }
            }

            ReminderOverlay(catalog)
        }
    }
}

@Composable
private fun PhoneBottomBar(
    items: List<Section>,
    section: Section,
    detailOpen: Boolean,
    moreSelected: Boolean,
    onSelect: (Section) -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Palette.Rail).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in items) {
            BottomItem(s.icon, s.shortLabel(), selected = section == s && !detailOpen, Modifier.weight(1f)) { onSelect(s) }
        }
        BottomItem(TouchIcons.More, "Plus", selected = moreSelected && !detailOpen, Modifier.weight(1f), onClick = onMore)
    }
}

@Composable
private fun BottomItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(60.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(50))
                .background(if (selected) Palette.Accent.copy(alpha = 0.18f) else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 3.dp),
        ) {
            FluxIcon(icon, size = 22.dp, tint = if (selected) Palette.Accent else Palette.Dim)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else Palette.Dim,
        )
    }
}

/** Rail de navigation (tablette, ou téléphone en paysage) : toutes les sections et le profil. */
@Composable
private fun PhoneNavRail(
    sections: List<Section>,
    section: Section,
    detailOpen: Boolean,
    profile: Profile,
    onSelect: (Section) -> Unit,
    onProfile: () -> Unit,
) {
    Column(
        Modifier.width(84.dp).fillMaxHeight().background(Palette.Rail)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Téléphone en paysage (peu de hauteur) : pas de logo, déjà affiché dans l'en-tête de l'accueil.
        if (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp >= 480) {
            Box(Modifier.padding(bottom = 10.dp)) { BrandLogo(compact = true) }
        }
        for (s in sections) {
            BottomItem(s.icon, s.shortLabel(), selected = section == s && !detailOpen, Modifier.width(76.dp)) { onSelect(s) }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onProfile),
            contentAlignment = Alignment.Center,
        ) { ProfileAvatar(profile, 34.dp) }
    }
}

/** Feuille « Plus » : sections secondaires, profil actif (appui = changer de profil). */
@Composable
private fun MoreSheet(
    items: List<Section>,
    section: Section,
    profile: Profile,
    catalog: Catalog,
    onSelect: (Section) -> Unit,
    onProfile: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().widthIn(max = 640.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Brush.verticalGradient(listOf(Palette.CardHigh, Palette.Card)))
            .blockTouches()
            .padding(top = 10.dp, bottom = 12.dp),
    ) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))
        )
        Spacer(Modifier.height(10.dp))
        SheetRow(onClick = onProfile) {
            ProfileAvatar(profile, 36.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("Changer de profil", fontSize = 12.sp, color = Palette.Dim)
            }
            FluxIcon(TouchIcons.ChevronRight, size = 22.dp, tint = Palette.Dim)
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(Palette.Stroke))
        for (s in items) {
            SheetRow(onClick = { onSelect(s) }) {
                FluxIcon(s.icon, size = 22.dp, tint = if (s == section) Palette.Accent else Color.White)
                Spacer(Modifier.width(16.dp))
                Text(
                    s.label, fontSize = 15.sp, modifier = Modifier.weight(1f),
                    fontWeight = if (s == section) FontWeight.SemiBold else FontWeight.Normal,
                )
                FluxIcon(TouchIcons.ChevronRight, size = 22.dp, tint = Palette.Dim)
            }
        }
        Text(
            "${catalog.channels.size} chaînes · ${catalog.movies.size} films · ${catalog.series.size} séries",
            fontSize = 11.sp, color = Palette.Faint, modifier = Modifier.padding(start = 20.dp, top = 8.dp),
        )
    }
}

@Composable
private fun SheetRow(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// ---------------------------------------------------------------- En-tête des pages (téléphone)

/** Barre du haut des pages : logo, titre éventuel, et avatar du profil (appui = changer de profil). */
@Composable
internal fun PhoneTopBar(profile: Profile?, onProfile: (() -> Unit)?, title: String? = null) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title == null) BrandLogo(Modifier.weight(1f))
        else Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
        if (profile != null && onProfile != null) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onProfile),
                contentAlignment = Alignment.Center,
            ) { ProfileAvatar(profile, 32.dp) }
        }
    }
}

// ---------------------------------------------------------------- Largeur d'écran, visuels paysage (tactile)

/** Largeur d'écran ≥ 840 dp (tablette en paysage) : mise en page « large ». */
@Composable
internal fun rememberIsWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 840

/**
 * Boutons d'action côte à côte (en-têtes, fiches) : pleine largeur partagée sur téléphone (largeur compacte),
 * sinon à la taille de leur libellé (160 à 280 dp), alignés à gauche.
 */
internal fun Modifier.actionButtonWidth(compact: Boolean, scope: androidx.compose.foundation.layout.RowScope): Modifier =
    if (compact) with(scope) { this@actionButtonWidth.weight(1f) } else this.widthIn(min = 160.dp, max = 280.dp)

/** Titre du catalogue dont on peut chercher le visuel paysage TMDB ([type] = [com.fluxfr.tv.data.Tmdb.MOVIE] / TV). */
internal data class TmdbRef(val type: String, val providerId: String) {
    val metaKey: String
        get() = if (type == com.fluxfr.tv.data.Tmdb.MOVIE) com.fluxfr.tv.data.VodMeta.movieKey(providerId)
        else com.fluxfr.tv.data.VodMeta.seriesKey(providerId)

    companion object {
        fun movie(id: String) = TmdbRef(com.fluxfr.tv.data.Tmdb.MOVIE, id)
        fun series(id: String) = TmdbRef(com.fluxfr.tv.data.Tmdb.TV, id)
    }
}

/** Fiche TMDB d'affichage : [loading] tant que la réponse (cache ou réseau) n'est pas arrivée. */
internal class TmdbArtState(val loading: Boolean, val art: com.fluxfr.tv.data.TmdbArt?)

/**
 * Visuel paysage, année, genres et note TMDB d'un titre (tactile seulement) : identifiant TMDB déjà connu (VodMeta,
 * recherche précédente), cache mémoire / disque, au plus une requête ; rien quand l'identifiant est inconnu.
 */
@Composable
internal fun rememberTmdbArt(ref: TmdbRef?): TmdbArtState {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val state by androidx.compose.runtime.produceState(
        initialValue = Pair(ref, TmdbArtState(ref != null, null)), ref,
    ) {
        if (ref == null) { value = ref to TmdbArtState(false, null); return@produceState }
        val art = runCatching {
            val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.fluxfr.tv.data.Tmdb.knownId(ctx, ref.metaKey)
            }
            if (id == null) null
            else com.fluxfr.tv.data.Tmdb.cachedArt(ref.type, id) ?: com.fluxfr.tv.data.Tmdb.art(ctx, ref.type, id)
        }.getOrNull()
        value = ref to TmdbArtState(false, art)
    }
    // Clé changée : l'ancienne réponse (autre titre) n'est jamais montrée.
    return if (state.first == ref) state.second else TmdbArtState(ref != null, null)
}

/** Affiche floutée et assombrie, en fond (image minuscule agrandie : floue même sans Modifier.blur, Android < 12). */
@Composable
internal fun BlurredPoster(poster: String?, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(modifier.fillMaxSize().background(Palette.Card)) {
        if (!poster.isNullOrBlank()) {
            val request = remember(poster) {
                coil.request.ImageRequest.Builder(ctx).data(poster).size(18, 27)
                    .precision(coil.size.Precision.EXACT).build()
            }
            coil.compose.AsyncImage(
                model = request, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(28.dp),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
    }
}

/**
 * Visuel d'un en-tête tactile : image paysage [backdrop] recadrée ; sinon (affiche portrait seule) affiche floutée en
 * fond et affiche nette 2:3 sur le côté ([posterAlignment]) — jamais une affiche portrait étirée en bandeau.
 * [loading] : réponse TMDB attendue (fond flou seul, pas encore d'affiche nette).
 */
@Composable
internal fun TouchHeroArt(
    backdrop: String?,
    poster: String?,
    loading: Boolean,
    showPoster: Boolean = true,
    posterAlignment: Alignment = Alignment.CenterEnd,
    posterPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(16.dp),
    modifier: Modifier = Modifier,
) {
    androidx.compose.animation.Crossfade(
        targetState = backdrop?.takeIf { it.isNotBlank() }, animationSpec = tween(300), label = "heroArt",
        modifier = modifier.fillMaxSize(),
    ) { b ->
        if (b != null) {
            coil.compose.AsyncImage(
                model = b, contentDescription = null, contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                BlurredPoster(poster)
                if (showPoster && !loading && !poster.isNullOrBlank()) {
                    Box(
                        Modifier.align(posterAlignment).padding(posterPadding).fillMaxHeight()
                            .aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Palette.Card)
                    ) {
                        coil.compose.AsyncImage(
                            model = poster, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Grande carte « à la une » (téléphone)

/**
 * Mise en avant (accueil, films, séries) : visuel arrondi, titre et boutons sous l'image.
 * Visuel paysage TMDB ([tmdb]) quand il existe ; sinon affiche floutée en fond et affiche nette à droite.
 * Pas de changement au focus : on montre toujours l'élément vedette.
 */
@Composable
internal fun PhoneHeroCard(
    eyebrow: String,
    title: String,
    meta: String?,
    image: String?,
    quality: Quality?,
    primaryLabel: String,
    onPrimary: () -> Unit,
    /** Second bouton (« Ma liste », « Infos ») : reçoit le Modifier qui lui donne sa largeur. */
    secondary: @Composable ((Modifier) -> Unit)? = null,
    onImageClick: (() -> Unit)? = null,
    /** Titre du catalogue : visuel paysage TMDB (sinon [image] est une affiche portrait). */
    tmdb: TmdbRef? = null,
) {
    val compact = rememberIsCompact()
    val wide = !compact
    val screenH = LocalConfiguration.current.screenHeightDp
    val wideHeight = (screenH * 0.5f).dp.coerceAtMost(360.dp)
    val art = rememberTmdbArt(tmdb)
    val backdrop = art.art?.backdrop
    // Sans titre du catalogue (chaîne, match…) : l'image est montrée telle quelle, comme avant.
    val posterLayout = tmdb != null && backdrop == null
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth().then(if (wide) Modifier.height(wideHeight) else Modifier.aspectRatio16x9())
                .clip(RoundedCornerShape(16.dp)).background(Palette.Card)
                .then(if (onImageClick != null) Modifier.clickable(onClick = onImageClick) else Modifier)
        ) {
            val posterShown = posterLayout && !art.loading && !image.isNullOrBlank()
            // Largeur de l'affiche nette (2:3 sur la hauteur moins les marges) : le texte s'arrête avant.
            val posterW = (maxHeight - 32.dp) * (2f / 3f) + 16.dp
            if (tmdb == null) Artwork(image, title, ContentScale.Crop)
            else TouchHeroArt(backdrop, image, art.loading)
            // Affiche (fond déjà assombri) : pas de voile, qui assombrirait aussi le bas de l'affiche nette.
            if (!posterLayout) Box(
                Modifier.fillMaxSize().background(
                    // Voile assez haut et dense pour que le texte reste lisible sur une image claire.
                    Brush.verticalGradient(
                        0f to Color.Transparent, 0.35f to Color.Transparent,
                        0.62f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.9f),
                    )
                )
            )
            Column(
                Modifier.align(Alignment.BottomStart).padding(14.dp)
                    .then(if (posterShown) Modifier.padding(end = posterW) else Modifier)
            ) {
                Text(
                    eyebrow.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.85f), letterSpacing = 1.2.sp, maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 26.sp,
                )
                if (meta != null || quality != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        quality?.takeIf { it.hasBadge }?.let { QualityBadge(it); Spacer(Modifier.width(8.dp)) }
                        meta?.let { Text(it, fontSize = 12.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Téléphone : deux boutons qui se partagent la largeur ; tablette : boutons à la taille du libellé, à gauche.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(primaryLabel, onPrimary, Modifier.actionButtonWidth(compact, this), primary = true, icon = FluxIcons.Play)
            if (secondary != null) secondary(Modifier.actionButtonWidth(compact, this))
        }
    }
}

private fun Modifier.aspectRatio16x9(): Modifier = this.aspectRatio(16f / 9f)

// ---------------------------------------------------------------- Fiches (téléphone)

/** Bouton rond translucide (retour, son) posé sur un visuel : 48 dp de cible tactile. */
@Composable
internal fun RoundIconButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(38.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { FluxIcon(icon, size = 22.dp) }
    }
}

/** Largeur d'un bouton d'action sur tablette : à la taille du libellé, entre 160 et 280 dp. */
internal val TouchButtonWidth: Modifier = Modifier.widthIn(min = 160.dp, max = 280.dp)

/** Rangée de boutons d'une fiche (tablette) : alignés à gauche, passent à la ligne s'ils ne tiennent pas. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TouchButtonRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/**
 * Corps d'une fiche sur tablette : à gauche l'affiche nette (2:3, 220 dp) et [leftExtra] ; à droite (680 dp au
 * plus, lignes de résumé lisibles) le titre, les infos, les boutons, le résumé ([right]).
 */
@Composable
internal fun TouchDetailColumns(
    poster: String?,
    title: String,
    leftExtra: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
    right: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(Modifier.width(220.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Palette.Card)
            ) { Artwork(poster, title, ContentScale.Crop) }
            Spacer(Modifier.height(12.dp))
            leftExtra()
        }
        Column(Modifier.weight(1f, fill = false).widthIn(max = 680.dp), content = right)
    }
}

/** Hauteur du visuel d'une fiche en largeur non compacte : la moitié de l'écran, 420 dp au plus. */
@Composable
internal fun detailArtHeight(): androidx.compose.ui.unit.Dp =
    (LocalConfiguration.current.screenHeightDp * 0.5f).dp.coerceIn(180.dp, 420.dp)

/**
 * Visuel en haut d'une fiche (16:9 sur téléphone) : image paysage [backdrop] ; sinon affiche floutée en fond et
 * affiche nette au centre ([showPoster], téléphone ; sur tablette l'affiche est dans la colonne de gauche).
 * Vidéo éventuelle par-dessus ([video]), découpée à la boîte du visuel et fondue vers le bas comme l'image ;
 * flèche de retour et bouton du son ([soundOn] non nul quand une vidéo joue) dans les coins de la boîte.
 */
@Composable
internal fun PhoneDetailArt(
    backdrop: String?,
    poster: String?,
    loading: Boolean,
    onBack: (() -> Unit)?,
    soundOn: Boolean? = null,
    onToggleSound: () -> Unit = {},
    showPoster: Boolean = true,
    /** Hauteur imposée (tablette) ; sinon 16:9 sur téléphone. */
    height: androidx.compose.ui.unit.Dp? = null,
    video: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val wide = !rememberIsCompact()
    // Téléphone en paysage (~410 dp de haut) : le visuel ne prend pas tout l'écran, le titre et les boutons restent visibles.
    val wideHeight = height ?: (LocalConfiguration.current.screenHeightDp * 0.55f).dp.coerceAtMost(320.dp)
    Box(
        Modifier.fillMaxWidth().then(if (wide || height != null) Modifier.height(wideHeight) else Modifier.aspectRatio16x9())
            // La vidéo (et l'image) ne débordent jamais de la boîte du visuel.
            .clipToBounds()
            .background(Color.Black)
    ) {
        TouchHeroArt(
            backdrop, poster, loading, showPoster = showPoster, posterAlignment = Alignment.Center,
            posterPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 28.dp),
        )
        video()
        Box(
            Modifier.fillMaxSize().background(
                // Fondu jusqu'au fond de page un peu avant le bord : aucun liseré clair sous le visuel au défilement.
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f), 0.25f to Color.Transparent, 0.7f to Color.Transparent,
                    0.97f to Palette.Bg, 1f to Palette.Bg,
                )
            )
        )
        if (onBack != null) RoundIconButton(TouchIcons.Back, onBack, Modifier.align(Alignment.TopStart).padding(4.dp))
        if (soundOn != null) {
            RoundIconButton(
                if (soundOn) TouchIcons.VolumeOn else TouchIcons.VolumeOff, onToggleSound,
                Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
    }
}
